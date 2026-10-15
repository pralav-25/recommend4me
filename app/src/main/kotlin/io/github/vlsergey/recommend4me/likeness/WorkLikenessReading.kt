package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(WorkLikenessReading::class.java)

/** The reranker read more pairs of a content type: the model reads them at its next training. */
data class WorkLikenessChanged(val type: String)

/**
 * READS EVERY WORK AGAINST EVERY GRADED WORK WITH THE RERANKER, in the background: the title and
 * the description of each ([TextRole.DESCRIPTION], as the user corrected them) as two texts read
 * together. A pair is read once and kept with the hash of both texts; read again only when either
 * changes — a new grade reads its work against the catalogue, a new work against the graded ones.
 * Every work's text is kept hashed as a pass read all its pairs ([WorkText]): a pass looks only at
 * the pairs of the works changed since. When a pass has read anything, the model is told
 * ([WorkLikenessChanged]).
 *
 * The work waits [DELAY_SECONDS] after the last change: grades and pages come in bursts. What a
 * pass has read is kept chunk by chunk: a pass cut short goes on from there the next time.
 */
@Component
class WorkLikenessReading(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "work-likeness").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Writes the chunks read, while the card reads the next one. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "work-likeness-writer").apply { isDaemon = true } }

    /** The smallest catalogue first: its pairs are read in minutes, a large one's may take hours. */
    @EventListener(ApplicationReadyEvent::class)
    fun readOnStart() = stores.types.sortedBy { t -> t.sources.sumOf { it.items.count().toLong() * it.ratings.count() } }.forEach { schedule(it.id, "start") }

    @EventListener
    fun gradesChanged(event: GradesChanged) = schedule(event.type, "grade")

    @EventListener
    fun itemsChanged(event: ItemsChanged) = schedule(stores.typeOf(event.source).id, "items")

    /** The pass planned after the quiet; the [reason], a key the interface words, told in the queue of the works. */
    private fun schedule(typeId: String, reason: String) {
        pending.compute(typeId) { _, before ->
            before?.cancel(false)
            workload.plan(Work("likeness", type = typeId), Instant.now().plusSeconds(DELAY_SECONDS), reason)
            scheduler.schedule({
                pending.remove(typeId)
                try {
                    val type = stores.type(typeId) ?: return@schedule
                    // Outside the queue of the heavy works: the reranker reads pairs on the card for hours, no catalogue held
                    workload.alongside(Work("likeness", type = typeId)) { read(type) }
                } catch (e: Exception) {
                    log.warn("{}: the works could not be read by the reranker", typeId, e)
                }
            }, DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** Reads the pairs of the type not read yet, or read from other texts; answers how many were read. */
    fun read(type: TypeStore): Int {
        val reranker = plugins.reranker() ?: return 0
        val texts = texts(type)
        val graded = type.sources.flatMap { s -> s.ratings.gradedItems().map { ItemKey(s.id, it) } }.filter { it in texts }
        if (graded.isEmpty()) return 0
        val likeness = type.workLikeness
        // ONLY THE PAIRS OF THE WORKS CHANGED since the last pass that read them all — a new text, a
        // work new, graded or no longer graded: the pairs of the rest are as they were
        val gradedSet = graded.toHashSet()
        val now = texts.mapValues { (key, text) -> WorkText(Vectors.keyOf(text), key in gradedSet) }
        val known = likeness.texts(reranker.id)
        val changed = now.filter { (key, text) -> known[key] != text }.keys
        val gone = known.keys - texts.keys
        if (changed.isEmpty()) {
            if (gone.isNotEmpty()) likeness.keepTexts(reranker.id, emptyMap(), gone)
            return 0
        }
        val wanted = ArrayList<PairScore>()
        val restamped = ArrayList<PairScore>()
        fun check(g: ItemKey, item: ItemKey, stored: Long?) {
            val hash = pairHash(now.getValue(g).hash, now.getValue(item).hash)
            when {
                stored == hash -> {}
                // Kept with the hash of the two texts together, as a pair was before the works' own:
                // read from these texts, its hash made anew, not read again
                stored == Vectors.keyOf(texts.getValue(g) + "\u0001" + texts.getValue(item)) -> {
                    restamped += PairScore(g, item, hash, 0f)
                    if (restamped.size >= CHUNK) {
                        likeness.restamp(reranker.id, restamped)
                        restamped.clear()
                    }
                }
                else -> wanted += PairScore(g, item, hash, 0f)
            }
        }
        // A graded work changed: its every pair as the graded one; a work changed: its pairs as the
        // one read, against the graded works not read as changed already
        val changedGraded = changed.filter { it in gradedSet }.toHashSet()
        changedGraded.forEach { g ->
            val stored = likeness.hashesOfGraded(reranker.id, g)
            texts.keys.forEach { item -> if (item != g) check(g, item, stored[item]) }
        }
        val others = graded.filter { it !in changedGraded }
        if (others.isNotEmpty()) changed.forEach { item ->
            val stored = likeness.hashesOfItem(reranker.id, item)
            others.forEach { g -> if (g != item) check(g, item, stored[g]) }
        }
        likeness.restamp(reranker.id, restamped)
        log.info("{}: {} works changed, {} gone, {} pairs to read by {}", type.id, changed.size, gone.size, wanted.size, reranker.id)
        if (wanted.isEmpty()) {
            likeness.keepTexts(reranker.id, now.filterKeys { it in changed }, gone)
            return 0
        }
        val started = System.currentTimeMillis()
        log.info("{}: {} pairs of {} graded works and {} works to read by {}", type.id, wanted.size, graded.size, texts.size, reranker.id)
        // Every text of the pass cut into the reranker's pieces once, on every core: not again for
        // every graded work it is read against, with the card waiting
        val keys = wanted.flatMap { listOf(it.graded, it.item) }.distinct()
        val at = keys.withIndex().associate { (i, key) -> key to i }
        val prepared = reranker.prepare(keys.map { texts.getValue(it) })
        log.info("{}: {} texts cut by {} in {} ms", type.id, keys.size, reranker.id, System.currentTimeMillis() - started)
        var read = 0
        // A chunk is written while the card reads the next one; one chunk waits to be written at most
        var writing: Future<*>? = null
        wanted.sortedWith(compareBy({ it.graded.toString() }, { it.item.toString() })).chunked(CHUNK).forEachIndexed { k, chunk ->
            val scores = reranker.score(prepared, IntArray(chunk.size) { at.getValue(chunk[it].graded) }, IntArray(chunk.size) { at.getValue(chunk[it].item) })
            writing?.get()
            writing = writer.submit { type.workLikeness.save(reranker.id, chunk.mapIndexed { i, p -> PairScore(p.graded, p.item, p.textsHash, scores[i]) }) }
            read += chunk.size
            log.info("{}: {} of {} pairs read by {} in {} s", type.id, read, wanted.size, reranker.id, (System.currentTimeMillis() - started) / 1000)
            // A pass of hours: the model reads what there is now and then, not only at its end
            if ((k + 1) % TELL_EVERY == 0) events.publishEvent(WorkLikenessChanged(type.id))
        }
        writing?.get()
        // Every pair of the changed works read: not read again until they change
        likeness.keepTexts(reranker.id, now.filterKeys { it in changed }, gone)
        events.publishEvent(WorkLikenessChanged(type.id))
        return read
    }

    /** The hash of a pair's texts: of the graded work's and the other's, each hashed once for all its pairs. */
    private fun pairHash(graded: Long, item: Long) = graded * 31 + item

    /** The title and the description of every work that has a description, as the user corrected them. */
    private fun texts(type: TypeStore): Map<ItemKey, String> {
        val out = HashMap<ItemKey, String>()
        type.sources.forEach { s ->
            val keys = s.schema.texts.filter { it.role == TextRole.DESCRIPTION }.map { it.key }
            if (keys.isEmpty()) return@forEach
            val titles = s.items.titles()
            val fields = s.corrections.allFields()
            val site = HashMap<String, Map<String, String>>()
            s.items.forEachTexts(keys) { id, t -> site[id] = t }
            titles.forEach { (id, title) ->
                val own = fields[id].orEmpty()
                val all = Corrected.texts(site[id].orEmpty(), own)
                val description = keys.mapNotNull { all[it]?.trim()?.takeIf { t -> t.isNotEmpty() } }.joinToString("\n\n")
                if (description.isNotEmpty()) out[ItemKey(s.id, id)] = Corrected.title(title, own) + "\n\n" + description
            }
        }
        return out
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
        writer.shutdown()
    }

    companion object {
        private const val DELAY_SECONDS = 30L

        /** Pairs read and written at once: a few minutes of the card's work at most, kept if the application stops. */
        private const val CHUNK = 4096

        /** How many chunks of a long pass are read between two tellings of the model: some ten minutes of the card's work. */
        private const val TELL_EVERY = 50
    }
}
