package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

private val log = LoggerFactory.getLogger(TagVectors::class.java)

/**
 * THE VECTORS OF THE TAGS, MADE IN THE BACKGROUND: of every normalized tag that has none by the text
 * encoder in use — found by a join, not by reading every tag — one of every name of it, its sites'
 * tags' and its entries', kept in the dictionaries ([TagRepository.vectors]): a tag is alike by the
 * best of its names, so a synonym added finds more and blurs nothing. A tag whose names change loses
 * its vectors there ([TagRepository.forgetVectors]) and is made again here.
 *
 * Made after any change of the tags — the user's word on them, the sites' tags bound as works come
 * — once the changes have paused, and five minutes after the start. The screen of the tags reads the
 * vectors kept; a tag without one yet is alike nothing for a while.
 */
@Component
class TagVectors(private val normalization: TagNormalization, private val plugins: Plugins, private val phrases: PhraseVectors) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tag-vectors").apply { isDaemon = true } }
    private val pending = AtomicReference<ScheduledFuture<*>?>()

    @EventListener(ApplicationReadyEvent::class)
    fun start() = schedule(START_DELAY_SECONDS)

    @EventListener
    fun tagsChanged(event: TagsChanged) = schedule(QUIET_SECONDS)

    /** Works came or changed: their sites' tags bound to tags ([TagNormalization]), maybe new ones. */
    @EventListener
    fun itemsChanged(event: ItemsChanged) = schedule(QUIET_SECONDS)

    private fun schedule(delaySeconds: Long) {
        val next = scheduler.schedule({
            try {
                fill()
            } catch (e: Exception) {
                log.warn("The vectors of the tags were not made: {}", e.message, e)
            }
        }, delaySeconds, TimeUnit.SECONDS)
        pending.getAndSet(next)?.cancel(false)
    }

    /** Makes the vector of every tag that has none by the encoder in use; how many were made. */
    fun fill(): Int {
        val encoder = plugins.textEncoder() ?: return 0
        val scope = normalization.scope() ?: return 0
        val plugin = plugins.pluginOf(encoder)
        val missing = scope.tags.withoutVectors(plugin, encoder.id).toSet()
        if (missing.isEmpty()) return 0
        val started = System.currentTimeMillis()
        // The names of the sites' tags of every tag without a vector, one read of each source; their
        // vectors those the sources keep of short texts, the ones not kept encoded now
        val sites = scope.tags.bindings().filterValues { it in missing }
        val ofTag = HashMap<Long, MutableList<FloatArray>>()
        scope.sources.forEach { s ->
            val siteFacet = normalization.siteFacetOf(s, scope.facet) ?: return@forEach
            val own = sites.filterKeys { it.source == s.id }
            if (own.isEmpty()) return@forEach
            val names = s.names(siteFacet)
            val written = own.keys.associateWith { (names[it.tag] ?: it.tag).trim() }.filterValues { it.isNotEmpty() }
            val vectors = phrases.of(s, written.values.distinct())
            written.forEach { (site, name) -> vectors[name]?.let { ofTag.getOrPut(own.getValue(site)) { ArrayList() } += it } }
        }
        // The names of the entries they are bound to, every language's: no site's, encoded here
        val bound = scope.tags.entriesOfTags().filterKeys { it in missing }
        val kept = scope.tags.keptEntries(bound.values.flatten().toSet())
        val entryNames = bound.mapValues { (_, refs) -> refs.flatMap { kept[it]?.let { e -> e.labels.values + e.aliases.values.flatten() }.orEmpty() }.distinct() }
        val encoded = encode(encoder, entryNames.values.flatten().distinct())
        entryNames.forEach { (tag, names) -> names.forEach { name -> encoded[name]?.let { ofTag.getOrPut(tag) { ArrayList() } += it } } }
        // Every tag without a vector is kept with one — of no name, an empty one — not asked again till its names change
        missing.chunked(KEEP).forEach { chunk -> scope.tags.keepVectors(plugin, encoder.id, chunk.associateWith { tag -> ofTag[tag].orEmpty().mapNotNull(::unit) }) }
        log.info("Vectors of {} tags made by {} ({}) in {} ms", missing.size, encoder.id, plugin, System.currentTimeMillis() - started)
        return missing.size
    }

    private fun encode(encoder: TextEncoder, texts: List<String>): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        texts.chunked(KEEP).forEach { chunk -> chunk.zip(encoder.encode(chunk)).forEach { (text, v) -> v?.let { out[text] = it } } }
        return out
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        /** After the start: the sources read, the encoder loaded, the first fittings under way. */
        private const val START_DELAY_SECONDS = 5 * 60L

        /** The quiet after a change: a page is sent several times as it grows, the user answers in a row. */
        private const val QUIET_SECONDS = 10L

        /** Tags written, and names encoded, at once. */
        private const val KEEP = 500

        /** [v] of unit length; null of a vector of nought. */
        fun unit(v: FloatArray): FloatArray? {
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: return null
            return FloatArray(v.size) { v[it] / norm }
        }

        /** How alike two sets of names are: the best cosine of a name of one and a name of the other; nought of an empty one. */
        fun likeness(a: List<FloatArray>, b: List<FloatArray>): Double =
            a.maxOfOrNull { x -> b.maxOfOrNull { y -> x.indices.sumOf { (x[it] * y[it]).toDouble() } } ?: 0.0 } ?: 0.0
    }
}
