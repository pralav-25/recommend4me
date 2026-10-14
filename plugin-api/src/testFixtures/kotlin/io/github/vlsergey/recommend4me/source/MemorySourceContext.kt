package io.github.vlsergey.recommend4me.source

import org.jooq.DSLContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * A [SourceContext] for the tests of a source: everything it writes is kept in maps the test can
 * look at. No database; [database] is not to be used.
 */
class MemorySourceContext(override val sourceId: String = "test") : SourceContext {

    val heads = LinkedHashMap<String, StoredItem>()
    val facets = HashMap<String, MutableMap<String, List<String>>>()
    val facetNames = HashMap<String, MutableMap<String, String>>()
    val facetDescriptions = HashMap<String, MutableMap<String, String>>()
    val numbers = HashMap<String, MutableMap<String, Double>>()
    val texts = HashMap<String, MutableMap<String, String>>()
    val pictures = HashMap<String, List<String?>>()
    val reviews = HashMap<String, MutableMap<String, ReviewData>>()
    val parts = HashMap<String, MutableMap<String, PartData>>()
    val signalValues = HashMap<Pair<String, String>, String>()
    val settingValues = HashMap<String, String>()
    val pages = LinkedHashMap<String, String>()

    override val items: ItemStore = object : ItemStore {
        override fun upsert(item: ItemHead, now: Instant): Boolean {
            val known = heads[item.id]
            heads[item.id] = StoredItem(item.id, item.url, item.title, item.version, item.updatedAt ?: known?.updatedAt ?: now, known?.firstSeenAt ?: now)
            return known?.version != item.version
        }

        override fun find(itemId: String) = heads[itemId]
        override fun count() = heads.size
        override fun latestUpdate() = heads.values.maxOfOrNull { it.updatedAt }

        override fun setFacet(itemId: String, facet: String, values: List<FacetValue>) {
            facets.getOrPut(itemId) { HashMap() }[facet] = values.map { it.key }.distinct()
            nameFacetValues(facet, values.filter { it.name != null }.associate { it.key to it.name!! })
        }

        override fun nameFacetValues(facet: String, names: Map<String, String>) {
            facetNames.getOrPut(facet) { HashMap() } += names
        }

        override fun describeFacetValues(facet: String, descriptions: Map<String, String>) {
            facetDescriptions.getOrPut(facet) { HashMap() } += descriptions
        }

        override fun facets(itemId: String): Map<String, List<String>> = facets[itemId].orEmpty()

        override fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit) =
            facets.forEach { (id, f) -> f[facet]?.let { action(id, it) } }

        override fun setNumbers(itemId: String, numbers: Map<String, Double?>) {
            val own = this@MemorySourceContext.numbers.getOrPut(itemId) { HashMap() }
            numbers.forEach { (k, v) -> if (v == null) own.remove(k) else own[k] = v }
        }

        override fun setTexts(itemId: String, texts: Map<String, String?>) {
            val own = this@MemorySourceContext.texts.getOrPut(itemId) { HashMap() }
            texts.forEach { (k, v) -> if (v.isNullOrBlank()) own.remove(k) else own[k] = v.trim() }
        }

        override fun texts(itemId: String): Map<String, String> = texts[itemId].orEmpty()

        override fun setPictures(itemId: String, urls: List<String?>) {
            pictures[itemId] = urls
        }

        override fun saveReviews(itemId: String, reviews: List<ReviewData>) {
            this@MemorySourceContext.reviews.getOrPut(itemId) { LinkedHashMap() } += reviews.associateBy { it.id }
        }

        override fun reviewIds(itemId: String): Set<String> = reviews[itemId].orEmpty().keys
        override fun reviewContents(itemId: String): Map<String, String> = reviews[itemId].orEmpty().mapValues { it.value.content }

        override fun saveParts(itemId: String, parts: List<PartData>) {
            val own = this@MemorySourceContext.parts.getOrPut(itemId) { LinkedHashMap() }
            parts.forEach { p ->
                val known = own[p.id]
                own[p.id] = p.copy(
                    position = if (p.position >= 0) p.position else known?.position ?: p.position,
                    title = p.title ?: known?.title,
                    content = p.content ?: known?.content,
                )
            }
        }

        override fun parts(itemId: String): Map<String, Boolean> = parts[itemId].orEmpty().mapValues { it.value.content != null }
    }

    override val database: DSLContext get() = error("A memory context has no database")

    override val settings: SourceSettings = object : SourceSettings {
        override fun get(key: String) = settingValues[key]
        override fun set(key: String, value: String?) {
            if (value == null) settingValues.remove(key) else settingValues[key] = value
        }
    }

    override val signals: SiteSignals = object : SiteSignals {
        override fun set(itemId: String, signal: String, value: String?) {
            if (value == null) signalValues.remove(itemId to signal) else signalValues[itemId to signal] = value
        }

        override fun withSignal(signal: String): Map<String, String> =
            signalValues.filterKeys { it.second == signal }.mapKeys { it.key.first }
    }

    override val folder: Path by lazy { Files.createTempDirectory("source-$sourceId") }

    override fun keepPage(url: String, html: String, itemId: String?) {
        pages[url] = html
    }

    override fun gradedItems(): Set<String> = emptySet()

    override fun itemsInOrder(): List<String> = heads.keys.toList()

    val jobStates = HashMap<String, String>()

    override fun jobState(key: String): String? = jobStates[key]
}
