package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FacetChancesChanged
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.work.TextPlace
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(Search::class.java)

/**
 * The search over the works of a content type, by the search plugin: the documents of the items —
 * their corrected titles, facets and texts with the vectors of the texts — made here, the index
 * the plugin's. The whole index is made again on every start, in the background; an item written
 * since it was indexed is indexed again before the next search (the queue of their keys is all
 * that is kept here).
 */
@Service
class Search(
    private val stores: Stores,
    private val plugins: Plugins,
    private val textVectors: TextVectors,
    private val phrases: PhraseVectors,
) {
    private val pending = ConcurrentHashMap<String, MutableSet<ItemKey>>()
    private val built = ConcurrentHashMap<String, CountDownLatch>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search-index").apply { isDaemon = true } }

    @EventListener
    fun itemsChanged(event: ItemsChanged) = queue(event.source, event.ids)

    @EventListener
    fun vectorsChanged(event: TextVectorsChanged) = queue(event.source, event.ids)

    @EventListener
    fun chancesChanged(event: FacetChancesChanged) = queue(event.source, event.ids)

    private fun queue(source: String, ids: Collection<String>) {
        val type = stores.typeOf(source).id
        pending.computeIfAbsent(type) { ConcurrentHashMap.newKeySet() } += ids.map { ItemKey(source, it) }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun build() {
        val provider = plugins.search ?: return
        // The scores of another reranker are of another model: read again as asked for
        plugins.reranker()?.let { r -> stores.types.forEach { it.searchScores.keepOnly(r.id) } }
        stores.types.forEach { built[it.id] = CountDownLatch(1) }
        worker.execute {
            stores.types.forEach { type ->
                try {
                    provider.rebuild(type.id, documentsOf(type))
                } catch (e: Exception) {
                    log.error("{}: the search index was not made", type.id, e)
                } finally {
                    built[type.id]?.countDown()
                }
            }
        }
    }

    /** Every document of a type, streamed: the items read a chunk at a time. */
    private fun documentsOf(type: TypeStore): Sequence<SearchDocument> = sequence {
        for (s in type.sources) {
            val ids = s.items.keys().map { it.id }
            for (chunk in ids.chunked(CHUNK)) yieldAll(documents(s, chunk).values)
        }
    }

    /**
     * The documents of the items [ids] of a source, read in one batch per table: their fields — the
     * chapters read among them — and, with [passages], the passages of the texts searched by meaning
     * and of the chapters: every window of a text longer than one with its own vector, a shorter one
     * whole with the text's.
     */
    fun documents(s: SourceStore, ids: List<String>, passages: Boolean = true): Map<ItemKey, SearchDocument> {
        val schema = s.schema
        val heads = s.items.heads(ids)
        val chances = s.suggestions.ofItems(ids)
        val facets = s.items.facetsOf(ids).mapValues { (id, site) -> ModelValues.of(schema, site, chances[id]) }
        val facetCorrections = s.corrections.facetsOf(ids)
        val hierarchies = s.hierarchies()
        val fields = s.corrections.fieldsOf(ids)
        val texts = s.items.textsOf(ids)
        val searchedFacets = schema.facets.filter { it.searchWeight > 0 }
        val names = searchedFacets.associate { def ->
            def.key to s.names(def.key, ids.flatMap { facets[it]?.get(def.key).orEmpty() }) +
                facetCorrections.values.flatten().filter { it.facet == def.key && it.name != null }.associate { it.key to it.name!! }
        }
        val encoder = plugins.textEncoder()?.takeIf { passages }
        val vectors = encoder?.let { e -> s.textVectors.ofMany(ids, e.id) }.orEmpty()
        val windows = encoder?.let { e -> s.textVectors.windowsOf(ids, e.id) }.orEmpty()
        val parts = s.parts.contentsOf(ids)
        val partWindows = encoder?.let { e -> s.parts.windowsByParts(ids, e.id) }.orEmpty()
        // A word in a chapter says the least of what the work is about: its weight the least of the texts'
        val partWeight = schema.texts.minOfOrNull { it.searchWeight } ?: 0f
        val meaningful = schema.texts.filter { it.searchByMeaning }.map { it.key }.toSet()

        /** The passages of a field: its windows with their vectors when it has more than one, else the whole of it with [whole]. */
        fun passagesOf(field: SearchField, whole: FloatArray?, windowVectors: List<FloatArray>?): List<SearchPassage> {
            val text = field.text
            if (encoder != null && windowVectors != null && windowVectors.size > 1) {
                val spans = encoder.windowSpans(text)
                // The windows of the text as it is now: of another text, the whole is its one passage
                if (spans.size == windowVectors.size) return spans.zip(windowVectors).map { (r, v) -> SearchPassage(field.key, r.first, r.last + 1, v) }
            }
            return listOfNotNull(whole?.let { SearchPassage(field.key, 0, text.length, it) })
        }

        return heads.mapNotNull { (id, head) ->
            val own = fields[id].orEmpty()
            val itemFacets = TagHierarchy.corrected(facets[id].orEmpty(), facetCorrections[id].orEmpty(), hierarchies)
            val itemTexts = Corrected.texts(texts[id].orEmpty(), own)
            val docFields = ArrayList<SearchField>()
            // The fields' names are what the reranker reads them as, plain words; where a match is the
            // interface is told by the field's key ([places])
            docFields += SearchField(TITLE, TITLE, 1.0f, listOf(Corrected.title(head.title, own)), naming = true)
            searchedFacets.forEach { def ->
                val keys = itemFacets[def.key].orEmpty()
                if (keys.isNotEmpty()) docFields += SearchField(
                    FACET + def.key, def.key, def.searchWeight, keys.map { names[def.key]?.get(it) ?: it }, list = true, naming = def.names,
                )
            }
            schema.texts.filter { it.searchWeight > 0 }.forEach { def ->
                itemTexts[def.key]?.let { docFields += SearchField(def.key, def.key, def.searchWeight, listOf(it)) }
            }
            val docPassages = ArrayList<SearchPassage>()
            docFields.filter { it.key in meaningful }.forEach { f -> docPassages += passagesOf(f, vectors[id]?.get(f.key), windows[id]?.get(f.key)) }
            // The chapters read: searched by their words and by the meaning of every window of them
            parts[id].orEmpty().forEachIndexed { n, (partId, title, content) ->
                val field = SearchField(PART + partId, title?.takeIf { it.isNotBlank() } ?: "part ${n + 1}", partWeight, listOf(content), group = PARTS)
                docFields += field
                val w = partWindows[id]?.get(partId)
                docPassages += passagesOf(field, w?.singleOrNull(), w)
            }
            ItemKey(s.id, id) to SearchDocument(ItemKey(s.id, id), docFields, docPassages)
        }.toMap()
    }

    /** The items written since they were indexed, indexed now — once the index of the start is made. */
    private fun catchUp(type: TypeStore) {
        val provider = plugins.search ?: return
        built[type.id]?.let { if (it.count > 0) return }
        val keys = pending[type.id]?.let { set -> set.toList().also { set.removeAll(it.toSet()) } }.orEmpty()
        if (keys.isEmpty()) return
        val docs = HashMap<ItemKey, SearchDocument>()
        keys.groupBy { it.source }.forEach { (source, list) ->
            stores.source(source)?.let { docs += documents(it, list.map { k -> k.id }) }
        }
        provider.update(type.id, docs.values.toList(), keys.filter { it !in docs })
    }

    private fun context(type: TypeStore) = object : SearchContext {
        override fun queryVector(line: String): FloatArray? = textVectors.query(line)
        override fun phraseVectors(texts: Collection<String>): Map<String, FloatArray> =
            type.sources.firstOrNull()?.let { phrases.of(it, texts) }.orEmpty()
        override fun documents(keys: Collection<ItemKey>): Map<ItemKey, SearchDocument> =
            keys.groupBy { it.source }.flatMap { (source, list) ->
                stores.source(source)?.let { documents(it, list.map { k -> k.id }).entries }.orEmpty()
            }.associate { it.key to it.value }
    }

    /**
     * The works matching [line], most relevant first; null without a search plugin. Found by the
     * plugin — by meaning and by words — and then, with a reranker ([rerank]), every one of them
     * read together with the line and put in the order of what it says: a vector of a text is the
     * text squeezed before it meets the line, and "a detective in Victorian London" found the works
     * whose texts named a Victor and London.
     */
    fun search(typeId: String, line: String, rerank: Boolean = true): SearchResult? {
        val provider = plugins.search ?: return null
        val type = stores.type(typeId) ?: return null
        built[typeId]?.await(WAIT_FOR_INDEX_SECONDS, TimeUnit.SECONDS)
        catchUp(type)
        val found = provider.search(typeId, line, context(type))
        return if (rerank) reranked(type, found) else found
    }

    /**
     * Every work found, read against the line by the reranker, in the order of its score; as found
     * without a reranker. A work read for the line before, its text the same, is not read again
     * ([SearchScoreRepository]): the next page of a search, the line searched again, read nothing.
     */
    private fun reranked(type: TypeStore, found: SearchResult): SearchResult {
        val reranker = plugins.reranker() ?: return found
        if (found.keys.size < 2) return found
        val started = System.currentTimeMillis()
        val documents = found.keys.groupBy { it.source }.flatMap { (source, list) ->
            stores.source(source)?.let { documents(it, list.map { k -> k.id }, passages = false).entries }.orEmpty()
        }.associate { it.key to it.value }
        val texts = found.keys.associateWith { key -> documents[key]?.let { readable(it, found.nearest[key]) }.orEmpty() }
        val hashes = texts.mapValues { Vectors.keyOf(it.value) }
        val stored = type.searchScores.of(reranker.id, found.line).filter { (key, s) -> hashes[key] == s.textsHash }
        val todo = found.keys.filter { it !in stored }
        val read = if (todo.isEmpty()) emptyMap() else {
            val scores = reranker.rank(found.line, todo.map { texts.getValue(it) })
            todo.indices.associate { todo[it] to StoredScore(hashes.getValue(todo[it]), scores[it]) }.also { type.searchScores.save(reranker.id, found.line, it) }
        }
        val scores = stored + read
        val order = found.keys.sortedByDescending { scores.getValue(it).score }
        log.info("'{}' in {}: {} works, {} read by {} in {} ms", found.line, type.id, found.keys.size, todo.size, reranker.id, System.currentTimeMillis() - started)
        return SearchResult(found.line, order, found.state, found.nearest)
    }

    /**
     * A work as the reranker reads it, as far as it reads: its title; the passage of it nearest to the
     * line — a window of a description, a scene of a chapter — when it was found by meaning; then its
     * fields by their names, the weightiest first. A chapter is read by its passage alone: whole, the
     * chapters are megabytes cut on every search to the few hundred pieces the reranker reaches.
     */
    private fun readable(doc: SearchDocument, nearest: SearchPassage?): String {
        val title = doc.fields.firstOrNull { it.key == TITLE }
        val piece = nearest?.let { p -> p.text(doc)?.let { text -> doc.fields.first { it.key == p.field }.name + ": " + text } }
        val rest = doc.fields.filter { it !== title && it.key != nearest?.field && !it.key.startsWith(PART) }.sortedByDescending { it.weight }
        return (listOfNotNull(title?.let { it.name + ": " + it.text }, piece) + rest.map { it.name + ": " + it.text }).joinToString("\n")
    }

    fun matches(typeId: String, result: SearchResult, keys: Collection<ItemKey>): Map<ItemKey, SearchMatch> {
        val provider = plugins.search ?: return emptyMap()
        val type = stores.type(typeId) ?: return emptyMap()
        return provider.matches(typeId, result, keys, context(type))
    }

    /**
     * Where in the work every match is, by the key of the field it was found in ([SearchMatch.field]):
     * the title, a facet, a text by the keys of their labels; a chapter by its title, or by its number
     * among the chapters read when it has none — the chapters of the matched items read in one go per source.
     */
    fun places(matches: Map<ItemKey, SearchMatch>): Map<ItemKey, TextPlace> {
        val out = HashMap<ItemKey, TextPlace>()
        matches.entries.groupBy { it.key.source }.forEach { (source, list) ->
            val s = stores.source(source) ?: return@forEach
            val inParts = list.filter { it.value.field.startsWith(PART) }.map { it.key.id }
            val chapters = if (inParts.isEmpty()) emptyMap() else s.parts.titlesOf(inParts)
            list.forEach { (key, match) ->
                val field = match.field
                out[key] = when {
                    field == TITLE -> TextPlace(labelKey = TextVectors.TITLE_LABEL)
                    field.startsWith(FACET) -> field.removePrefix(FACET).let { f ->
                        TextPlace(labelKey = s.schema.facet(f)?.let { I18nKeys.facetLabel(s.id, it) } ?: I18nKeys.qualified(s.id, I18nKeys.facet(f)))
                    }
                    field.startsWith(PART) -> {
                        val partId = field.removePrefix(PART)
                        val read = chapters[key.id].orEmpty()
                        val at = read.indexOfFirst { it.first == partId }
                        read.getOrNull(at)?.second?.takeIf { it.isNotBlank() }?.let { TextPlace(title = it) }
                            ?: TextPlace(part = if (at >= 0) at + 1 else null)
                    }
                    else -> TextPlace(labelKey = I18nKeys.textLabel(s.id, field))
                }
            }
        }
        return out
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        private const val CHUNK = 500

        /** How long a search waits for the index of the start; the index of the last run serves meanwhile. */
        private const val WAIT_FOR_INDEX_SECONDS = 0L

        /** The field of the title. */
        private const val TITLE = "title"

        /** The fields of the facets: "facet.<facet key>". */
        private const val FACET = "facet."

        /** The fields of the chapters: "part:<part id>", searched as one field of the index, [PARTS]. */
        private const val PART = "part:"
        private const val PARTS = "parts"
    }
}
