package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.encoder.TextReranker
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
 * When a pass has read anything, the model is told ([WorkLikenessChanged]).
 *
 * The work waits [DELAY_SECONDS] after the last change: grades and pages come in bursts. What a
 * pass has read is kept chunk by chunk: a pass cut short goes on from there the next time.
 */
@Component
class WorkLikenessReading(private val stores: Stores, private val plugins: Plugins, private val events: ApplicationEventPublisher) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "work-likeness").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** The smallest catalogue first: its pairs are read in minutes, a large one's may take hours. */
    @EventListener(ApplicationReadyEvent::class)
    fun readOnStart() = stores.types.sortedBy { t -> t.sources.sumOf { it.items.count().toLong() * it.ratings.count() } }.forEach { schedule(it.id) }

    @EventListener
    fun gradesChanged(event: GradesChanged) = schedule(event.type)

    @EventListener
    fun itemsChanged(event: ItemsChanged) = schedule(stores.typeOf(event.source).id)

    private fun schedule(typeId: String) {
        pending.compute(typeId) { _, before ->
            before?.cancel(false)
            scheduler.schedule({
                pending.remove(typeId)
                try {
                    read(stores.type(typeId) ?: return@schedule)
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
        val known = type.workLikeness.hashes(reranker.id)
        val wanted = ArrayList<PairScore>()
        graded.forEach { g ->
            val first = texts.getValue(g)
            texts.forEach { (item, second) ->
                if (item == g) return@forEach
                val hash = Vectors.keyOf(first + "\u0001" + second)
                if (known[g to item] != hash) wanted += PairScore(g, item, hash, 0f)
            }
        }
        if (wanted.isEmpty()) return 0
        val started = System.currentTimeMillis()
        log.info("{}: {} pairs of {} graded works and {} works to read by {}", type.id, wanted.size, graded.size, texts.size, reranker.id)
        var read = 0
        // The pairs of one graded work together: its text is cut into pieces once a chunk
        wanted.sortedWith(compareBy({ it.graded.toString() }, { it.item.toString() })).chunked(CHUNK).forEachIndexed { k, chunk ->
            read += readChunk(type, reranker, texts, chunk)
            log.info("{}: {} of {} pairs read by {} in {} s", type.id, read, wanted.size, reranker.id, (System.currentTimeMillis() - started) / 1000)
            // A pass of hours: the model reads what there is now and then, not only at its end
            if ((k + 1) % TELL_EVERY == 0) events.publishEvent(WorkLikenessChanged(type.id))
        }
        events.publishEvent(WorkLikenessChanged(type.id))
        return read
    }

    private fun readChunk(type: TypeStore, reranker: TextReranker, texts: Map<ItemKey, String>, chunk: List<PairScore>): Int {
        val scores = reranker.score(chunk.map { texts.getValue(it.graded) to texts.getValue(it.item) })
        type.workLikeness.save(reranker.id, chunk.mapIndexed { k, p -> PairScore(p.graded, p.item, p.textsHash, scores[k]) })
        return chunk.size
    }

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
    }

    companion object {
        private const val DELAY_SECONDS = 30L

        /** Pairs read and written at once: a few minutes of the card's work at most, kept if the application stops. */
        private const val CHUNK = 4096

        /** How many chunks of a long pass are read between two tellings of the model: some ten minutes of the card's work. */
        private const val TELL_EVERY = 50
    }
}
