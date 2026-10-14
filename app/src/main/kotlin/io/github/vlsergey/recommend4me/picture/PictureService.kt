package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.encoder.PreparedPicture
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(PictureService::class.java)

data class PictureStatus(val total: Int, val analyzed: Int, val failed: Int, val running: Boolean, val perMinute: Double?)

/** A picture to serve: a local file, or the address of the original to send the browser to. */
sealed interface Served {
    data class File(val path: Path, val contentType: String) : Served
    data class Redirect(val url: String) : Served
}

/**
 * The pictures of the items of every source: the cover (position 0) and every screenshot, analysed
 * by the picture encoder from the ORIGINAL in the background, without end: every picture of the
 * graded works first, then the other items from the top of the recommendations down, every picture
 * of an item at once; the sources share the work.
 *
 * WHAT IS KEPT FOLLOWS THE GRADES: the vector and a preview always, the original only for works
 * graded "can be played" or more — graded so later, the originals are downloaded again; graded
 * lower, they are deleted.
 */
@Service
class PictureService(
    private val stores: Stores,
    private val plugins: Plugins,
    private val contexts: SourceContexts,
    private val recommendations: Recommendations,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pictures").apply { isDaemon = true } }
    private val downloads = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS) { r -> Thread(r, "picture-download").apply { isDaemon = true } }
    private val wake = Object()
    private val serving = ReentrantLock()

    @Volatile
    private var running = false

    @Volatile
    private var stopping = false

    private val doneSinceStart = AtomicInteger()
    private var startedAt = 0L

    /** The pictures of the work changed hands, or its grades changed: keep or drop its originals, and look at the queue. */
    @EventListener
    fun gradesChanged(event: GradesChanged) {
        val store = stores.source(event.source) ?: return
        if (!keepsOriginals(store, event.itemId)) store.files.delete(store.pictures.forgetOriginals(event.itemId))
        signal()
    }

    private fun keepsOriginals(store: SourceStore, itemId: String): Boolean =
        (store.ratings.ofItem(itemId).maxOfOrNull { it.grade } ?: 0) >= KEEP_FROM

    fun status(): PictureStatus {
        val encoder = plugins.imageEncoder()
        val minutes = (System.currentTimeMillis() - startedAt) / 60000.0
        return PictureStatus(
            total = stores.sources.sumOf { it.pictures.count() },
            analyzed = encoder?.let { e -> stores.sources.sumOf { it.pictures.countAnalyzed(e.id) } } ?: 0,
            failed = stores.sources.sumOf { it.pictures.countFailed() },
            running = running,
            perMinute = if (startedAt > 0 && minutes > 1) doneSinceStart.get() / minutes else null,
        )
    }

    /** A picture to show: the preview (downloaded now if missing), or the original when [full] is asked. */
    fun serve(key: ItemKey, position: Int, full: Boolean): Served? {
        val store = stores.source(key.source) ?: return null
        val picture = store.pictures.find(key.id, position, null) ?: return null
        if (full) {
            return picture.fullFile?.let { Served.File(store.files.path(it), PictureFiles.contentType(it)) }
                ?: Served.Redirect(store.source.pictureAddresses(picture.url).firstOrNull() ?: picture.url)
        }
        picture.previewFile?.let { return Served.File(store.files.path(it), PictureFiles.contentType(it)) }
        return serving.withLock {
            val again = store.pictures.find(key.id, position, null) ?: return@withLock null
            again.previewFile?.let { return@withLock Served.File(store.files.path(it), PictureFiles.contentType(it)) }
            try {
                val (bytes, type) = PictureDownloader.fetch(again.url, store.source.pictureHeaders(again.url, contexts.of(store.id)))
                val file = store.files.save(key.id, "$position-preview", bytes, type)
                store.pictures.saveFiles(key.id, position, file, again.fullFile)
                Served.File(store.files.path(file), type)
            } catch (e: Exception) {
                log.warn("preview {} was not downloaded: {}", again.url, e.message)
                Served.Redirect(again.url)
            }
        }
    }

    @Order(4)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.execute {
            try {
                stores.sources.forEach { s ->
                    val retried = s.pictures.clearPassingErrors()
                    if (retried > 0) log.info("{}: {} pictures that failed to download are tried again", s.id, retried)
                    plugins.imageEncoder()?.let { encoder ->
                        s.pictures.itemsWithoutVectors(encoder.id).takeIf { it.isNotEmpty() }?.let { ids ->
                            s.pictures.refreshItemVectors(ids, encoder.id)
                            log.info("{}: vectors of the pictures of {} items made", s.id, ids.size)
                        }
                    }
                }
                startedAt = System.currentTimeMillis()
                loop()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                log.error("the picture analysis stopped", e)
            }
        }
    }

    private class Job(val store: SourceStore, val picture: Picture, val keep: Boolean)

    private class Fetched(
        val job: Job,
        val original: Pair<ByteArray, String>?,
        val preview: Pair<ByteArray, String>?,
        val prepared: PreparedPicture?,
        val error: String?,
    )

    /**
     * The next pictures of every source, sharing [limit] among the sources that have work: the
     * graded works' first, then by prediction, then the newest.
     */
    private fun pending(encoder: ImageEncoder, limit: Int, skip: Set<Pair<String, Pair<String, Int>>>): List<Job> {
        val perSource = stores.sources.map { s ->
            val best = s.ratings.all().groupBy { it.itemId }.mapValues { (_, list) -> list.maxOf { it.grade } }
            val keep = best.filterValues { it >= KEEP_FROM }.keys
            val graded = s.pictures.pendingOf(best.keys.sorted(), encoder.id, keep, limit)
            val rest = if (graded.size >= limit) emptyList() else {
                val todo = s.pictures.itemsWithPending(encoder.id) - best.keys
                val predictions = stores.typeOf(s.id).models.predictions(s.id)
                val order = todo.sortedByDescending { predictions[it] ?: Double.NEGATIVE_INFINITY }.take(limit)
                s.pictures.pendingOf(order, encoder.id, emptySet(), limit - graded.size)
            }
            (graded + rest).filter { (s.id to (it.itemId to it.position)) !in skip }.map { Job(s, it, it.itemId in keep) }
        }.filter { it.isNotEmpty() }
        if (perSource.isEmpty()) return emptyList()
        val share = (limit / perSource.size).coerceAtLeast(1)
        return perSource.flatMap { it.take(share) }.take(limit)
    }

    /**
     * The downloads stream: up to [WINDOW] pictures are being downloaded and decoded at any time,
     * and the encoder takes whatever is ready, up to [BATCH] at once. No batch waits for its
     * slowest picture.
     */
    private fun loop() {
        val sinceRetrain = HashMap<String, Int>()
        val ready = ExecutorCompletionService<Fetched>(downloads)
        val inFlight = HashSet<Pair<String, Pair<String, Int>>>()
        while (!stopping) {
            val encoder = plugins.imageEncoder()
            if (encoder == null) {
                running = false
                synchronized(wake) { wake.wait(IDLE_MILLIS) }
                continue
            }
            if (inFlight.size <= WINDOW - BATCH) {
                pending(encoder, WINDOW - inFlight.size, inFlight).forEach { job ->
                    inFlight += job.store.id to (job.picture.itemId to job.picture.position)
                    ready.submit { download(job, encoder) }
                }
            }
            if (inFlight.isEmpty()) {
                running = false
                sinceRetrain.filterValues { it > 0 }.keys.forEach { recommendations.scheduleRetrain(it, byUser = false) }
                sinceRetrain.clear()
                synchronized(wake) { wake.wait(IDLE_MILLIS) }
                continue
            }
            running = true
            val batch = ArrayList<Fetched>(BATCH)
            ready.poll(WAIT_SECONDS, TimeUnit.SECONDS)?.let { batch += it.get() }
            // A fuller batch is cheaper for the encoder: gather for a few seconds more
            val until = System.nanoTime() + GATHER_SECONDS * 1_000_000_000L
            while (batch.isNotEmpty() && batch.size < BATCH && batch.size < inFlight.size) {
                val left = until - System.nanoTime()
                val next = if (left > 0) ready.poll(left, TimeUnit.NANOSECONDS) else ready.poll()
                batch += (next ?: break).get()
            }
            if (batch.isEmpty()) continue
            analyse(batch, encoder).forEach { (type, n) -> sinceRetrain.merge(type, n, Int::plus) }
            batch.forEach { inFlight -= it.job.store.id to (it.job.picture.itemId to it.job.picture.position) }
            // The model learns from the pictures as they come, not once at the end of days
            sinceRetrain.filterValues { it >= RETRAIN_EVERY }.keys.forEach {
                recommendations.scheduleRetrain(it, byUser = false)
                sinceRetrain[it] = 0
            }
        }
    }

    /** Downloads one picture, and decodes and prepares it when it is to be analysed. */
    private fun download(job: Job, encoder: ImageEncoder): Fetched {
        val store = job.store
        val picture = job.picture
        val headers = { url: String -> store.source.pictureHeaders(url, contexts.of(store.id)) }
        var used: String? = null
        var lastError: String? = null
        var original: Pair<ByteArray, String>? = null
        for (address in store.source.pictureAddresses(picture.url)) {
            try {
                original = PictureDownloader.fetch(address, headers(address))
                used = address
                break
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        if (original == null) return Fetched(job, null, null, null, lastError ?: "no address")
        val preview = try {
            when {
                picture.previewFile != null -> null
                used == picture.url -> original
                else -> PictureDownloader.fetch(picture.url, headers(picture.url))
            }
        } catch (e: Exception) {
            // The preview is the original then
            original
        }
        if (picture.analyzed) return Fetched(job, original, preview, null, null)
        // Decoding and preparing run here, in parallel, and leave the encoder's thread to the encoder
        val prepared = try {
            PictureFiles.decode(original.first)?.let(encoder::prepare)
        } catch (e: Exception) {
            return Fetched(job, original, preview, null, "cannot decode ${original.second}: ${e.message ?: e.javaClass.simpleName}")
        }
        return if (prepared == null) Fetched(job, original, preview, null, "cannot decode ${original.second}: no reader")
        else Fetched(job, original, preview, prepared, null)
    }

    /** Analyses and stores the downloaded pictures; returns how many new vectors every content type got. */
    private fun analyse(fetched: List<Fetched>, encoder: ImageEncoder): Map<String, Int> {
        val started = System.nanoTime()
        fetched.filter { it.error != null }.forEach { it.job.store.pictures.saveError(it.job.picture.itemId, it.job.picture.position, it.error!!) }
        val toAnalyze = fetched.filter { it.prepared != null }
        val vectors = if (toAnalyze.isEmpty()) emptyList() else encoder.encode(toAnalyze.map { it.prepared!! })
        val byJob = toAnalyze.indices.associate { toAnalyze[it].job to vectors[it] }
        val now = Instant.now()
        val made = HashMap<String, Int>()
        for (f in fetched) {
            val original = f.original ?: continue
            val job = f.job
            if (!job.picture.analyzed && job !in byJob) continue
            val p = job.picture
            val previewFile = p.previewFile ?: f.preview?.let { (bytes, type) -> job.store.files.save(p.itemId, "${p.position}-preview", bytes, type) }
            val fullFile = if (job.keep) p.fullFile ?: job.store.files.save(p.itemId, "${p.position}", original.first, original.second) else null
            job.store.pictures.saveAnalysis(p.itemId, p.position, byJob[job], encoder.id, previewFile, fullFile, now)
            if (byJob[job] != null) made.merge(job.store.type, 1, Int::plus)
        }
        doneSinceStart.addAndGet(byJob.size)
        log.info(
            "{} pictures: {} analysed, {} failed; encoder {} s",
            fetched.size, byJob.size, fetched.count { it.error != null }, "%.1f".format((System.nanoTime() - started) / 1e9),
        )
        return made
    }

    fun signal() = synchronized(wake) { wake.notifyAll() }

    @EventListener
    fun picturesListed(event: PicturesListed) = signal()

    @PreDestroy
    fun shutdown() {
        stopping = true
        signal()
        worker.shutdownNow()
        downloads.shutdownNow()
        worker.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        /** Originals are kept for works graded this or more: "can be played". */
        const val KEEP_FROM = Grades.MIDDLE

        /** Pictures per encoder run at most. */
        private const val BATCH = 64

        /** Pictures downloading or downloaded and waiting for the encoder, at most. */
        private const val WINDOW = 2 * BATCH

        private const val WAIT_SECONDS = 30L
        private const val GATHER_SECONDS = 10L
        private const val PARALLEL_DOWNLOADS = 24
        private const val IDLE_MILLIS = 10 * 60_000L

        /** New picture vectors between retrainings. */
        private const val RETRAIN_EVERY = 1000
    }
}
