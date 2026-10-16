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
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.suggestion.FacetChancesChanged
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.WorkChances
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.universe.Naming
import io.github.vlsergey.recommend4me.universe.Quote
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.work.TextPlace
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(Search::class.java)

/**
 * The search over the works of a content type, by the search plugin: the documents of the items —
 * their corrected titles, facets and texts with the vectors of the texts — made here, the index
 * the plugin's. The whole index is made again on every start, in the background; an item written
 * since it was indexed is indexed again in the background too, shortly after the writes go quiet —
 * a search never waits for it, it searches the index as it is (the queue of their keys is all
 * that is kept here).
 */
@Service
class Search(
    private val stores: Stores,
    private val plugins: Plugins,
    private val textVectors: TextVectors,
    private val phrases: PhraseVectors,
    private val workload: Workload,
    private val suggestions: FacetSuggestions,
) {
    private val pending = ConcurrentHashMap<String, MutableSet<ItemKey>>()
    private val built = ConcurrentHashMap<String, CountDownLatch>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search-index").apply { isDaemon = true } }
    private val updater = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "search-update").apply { isDaemon = true } }
    private val updates = ConcurrentHashMap<String, ScheduledFuture<*>>()

    @EventListener
    fun itemsChanged(event: ItemsChanged) = queue(event.source, event.ids)

    @EventListener
    fun vectorsChanged(event: TextVectorsChanged) = queue(event.source, event.ids)

    @EventListener
    fun chancesChanged(event: FacetChancesChanged) = queue(event.source, event.ids)

    /** The user's answer on a work's value: the work is found by what the user said it has. */
    @EventListener
    fun answered(event: FacetAnswered) = queue(event.source, listOf(event.itemId))

    /** The items written are indexed again in the background, shortly after the last of a quick series of writes. */
    private fun queue(source: String, ids: Collection<String>) {
        val type = stores.typeOf(source).id
        pending.computeIfAbsent(type) { ConcurrentHashMap.newKeySet() } += ids.map { ItemKey(source, it) }
        updates.compute(type) { _, old ->
            old?.cancel(false)
            workload.plan(updating(type), Instant.now().plusMillis(UPDATE_QUIET_MS), "items")
            updater.schedule({
                try {
                    stores.type(type)?.let(::catchUp)
                } catch (e: Exception) {
                    log.error("{}: the search index was not updated", type, e)
                }
            }, UPDATE_QUIET_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun updating(type: String) = Work("searchUpdate", type = type)

    @EventListener(ApplicationReadyEvent::class)
    fun build() {
        val provider = plugins.search ?: return
        // The scores of another reranker are of another model: read again as asked for
        plugins.reranker()?.let { r -> stores.types.forEach { it.searchScores.keepOnly(r.id) } }
        stores.types.forEach { built[it.id] = CountDownLatch(1) }
        worker.execute {
            stores.types.forEach { type ->
                try {
                    made(type, mapOf("start" to 1))
                } catch (e: Exception) {
                    log.error("{}: the search index was not made", type.id, e)
                }
                // What was written while the index was made
                catchUp(type)
            }
        }
    }

    /**
     * The index of the start made, in the queue of the computations, unless it is made already: by
     * the start, or by a refresh of the models that needed it first ([current]).
     */
    private fun made(type: TypeStore, reasons: Map<String, Int>) {
        val provider = plugins.search ?: return
        val latch = built[type.id] ?: return
        try {
            workload.run(Work("searchIndex", type = type.id), reasons) {
                if (latch.count > 0) provider.rebuild(type.id, documentsOf(type))
            }
        } finally {
            latch.countDown()
        }
    }

    /**
     * THE INDEX AS THE WORKS ARE NOW, before the models of a source are fitted on what it counts —
     * the names its works' words name ([io.github.vlsergey.recommend4me.search.SearchProvider.mentions]):
     * made, if the start has not made it yet, and the works written since indexed — in the work of
     * the queue that asks, not after a quiet.
     */
    @EventListener
    fun current(event: ModelsRefreshing) {
        val type = stores.typeOf(event.source)
        made(type, mapOf("models" to 1))
        catchUp(type)
    }

    /**
     * Every document of a type, streamed: the items read a chunk at a time; the values the model
     * gives them made of every work of a source in one pass first, held while its chunks are read.
     */
    private fun documentsOf(type: TypeStore): Sequence<SearchDocument> = sequence {
        for (s in type.sources) {
            val ids = s.items.keys().map { it.id }
            val chances = suggestions.chances(s, null)
            for (chunk in ids.chunked(CHUNK)) yieldAll(documents(s, chunk, chances = chances).values)
        }
    }

    /**
     * The documents of the items [ids] of a source, read in one batch per table: their fields — the
     * chapters read among them — and, with [passages], the passages of the texts searched by meaning
     * and of the chapters: every window of a text longer than one with its own vector, a shorter one
     * whole with the text's. The values the model gives them of their [chances], made now of them
     * when not given.
     *
     * THE WORK'S OWN WORDS ([SearchField.counted]) — every text of it, every chapter, every value its
     * site gives it — are all in it, those not searched of weight 0: a name written in them is the
     * work naming it ([SearchProvider.mentions]). The values the user or the model gave it are a
     * field of their own beside the site's, searched, never counted: the model would count its own
     * guesses. And the language its description is written in ([descriptionLanguage]).
     */
    fun documents(s: SourceStore, ids: List<String>, passages: Boolean = true, chances: WorkChances? = null): Map<ItemKey, SearchDocument> {
        val schema = s.schema
        val heads = s.items.heads(ids)
        val chances = chances ?: suggestions.chances(s, ids)
        val facets = s.items.facetsOf(ids).mapValues { (id, site) -> ModelValues.of(schema, site, chances[id]) }
        val facetCorrections = s.corrections.facetsOf(ids)
        val hierarchies = s.hierarchies()
        val fields = s.corrections.fieldsOf(ids)
        val texts = s.items.textsOf(ids)
        val site = s.items.facetsOf(ids)
        // The values of every item as the user says, the user's own among them: what is indexed — and named
        val corrected = ids.associateWith { id -> TagHierarchy.corrected(facets[id].orEmpty(), facetCorrections[id].orEmpty(), hierarchies) }
        val names = schema.facets.associate { def ->
            def.key to s.names(def.key, ids.flatMap { corrected[it]?.get(def.key).orEmpty() + site[it]?.get(def.key).orEmpty() }.distinct()) +
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
            val itemFacets = corrected[id].orEmpty()
            val itemTexts = Corrected.texts(texts[id].orEmpty(), own)
            val docFields = ArrayList<SearchField>()
            // The fields' names are what the reranker reads them as, plain words; where a match is the
            // interface is told by the field's key ([places])
            docFields += SearchField(TITLE, TITLE, 1.0f, listOf(Corrected.title(head.title, own)), naming = true)
            schema.facets.forEach { def ->
                val keys = itemFacets[def.key].orEmpty()
                // The site's own, as the user left them; the rest the user's and the model's
                val removed = facetCorrections[id].orEmpty().filter { it.facet == def.key && !it.added }.map { it.key }.toSet()
                val own = site[id]?.get(def.key).orEmpty().filter { it !in removed }.distinct()
                val given = keys.filter { it !in own }
                fun named(list: List<String>) = list.map { names[def.key]?.get(it) ?: it }
                if (own.isNotEmpty()) docFields += SearchField(
                    FACET + def.key, def.key, def.searchWeight, named(own), list = true, naming = def.names, counted = true,
                )
                if (given.isNotEmpty() && def.searchWeight > 0) docFields += SearchField(
                    FACET + def.key, def.key, def.searchWeight, named(given), list = true, naming = def.names, group = FACET + def.key + GIVEN,
                )
            }
            schema.texts.forEach { def ->
                itemTexts[def.key]?.let { docFields += SearchField(def.key, def.key, def.searchWeight, listOf(it), counted = true) }
            }
            val docPassages = ArrayList<SearchPassage>()
            docFields.filter { it.key in meaningful }.forEach { f -> docPassages += passagesOf(f, vectors[id]?.get(f.key), windows[id]?.get(f.key)) }
            // The chapters read: searched by their words and by the meaning of every window of them
            parts[id].orEmpty().forEachIndexed { n, (partId, title, content) ->
                val field = SearchField(PART + partId, title?.takeIf { it.isNotBlank() } ?: "part ${n + 1}", partWeight, listOf(content), group = PARTS, counted = true)
                docFields += field
                val w = partWindows[id]?.get(partId)
                docPassages += passagesOf(field, w?.singleOrNull(), w)
            }
            ItemKey(s.id, id) to SearchDocument(ItemKey(s.id, id), docFields, docPassages, descriptionLanguage(schema, itemFacets))
        }.toMap()
    }

    /** The items written since they were indexed, indexed now, in the queue of the computations — once the index of the start is made. */
    private fun catchUp(type: TypeStore) {
        val provider = plugins.search ?: return
        built[type.id]?.let { if (it.count > 0) return }
        val reasons = workload.take(updating(type.id))
        val keys = pending[type.id]?.let { set -> set.toList().also { set.removeAll(it.toSet()) } }.orEmpty()
        if (keys.isEmpty()) return
        // The works taken here are this update's own: another update is of others
        workload.run(updating(type.id), reasons, joinAlike = false) {
            val docs = HashMap<ItemKey, SearchDocument>()
            keys.groupBy { it.source }.forEach { (source, list) ->
                stores.source(source)?.let { docs += documents(it, list.map { k -> k.id }) }
            }
            provider.update(type.id, docs.values.toList(), keys.filter { it !in docs })
        }
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
     * The works matching [line], read as the [language] (ISO 639-1), most relevant first; null without a search plugin. Found by the
     * plugin — by meaning and by words — and then, with a reranker ([rerank]), every one of them
     * read together with the line and put in the order of what it says: a vector of a text is the
     * text squeezed before it meets the line, and "a detective in Victorian London" found the works
     * whose texts named a Victor and London.
     */
    fun search(typeId: String, line: String, language: String, rerank: Boolean = true): SearchResult? {
        val provider = plugins.search ?: return null
        val type = stores.type(typeId) ?: return null
        // The index as it is: the works written since are indexed in the background, a request never waits for it
        val found = provider.search(typeId, line, language, context(type))
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
            list.forEach { (key, match) -> out[key] = placeOf(s, match.field, chapters[key.id].orEmpty()) }
        }
        return out
    }

    /** Where in the work the field [field] of its document is: its title, a facet, a text by the keys of their labels; a chapter by its title among its [chapters], or by its number. */
    private fun placeOf(s: SourceStore, field: String, chapters: List<Pair<String, String?>>): TextPlace = when {
        field == TITLE -> TextPlace(labelKey = TextVectors.TITLE_LABEL)
        field.startsWith(FACET) -> field.removePrefix(FACET).let { f ->
            TextPlace(labelKey = s.schema.facet(f)?.let { I18nKeys.facetLabel(s.id, it) } ?: I18nKeys.qualified(s.id, I18nKeys.facet(f)))
        }
        field.startsWith(PART) -> {
            val partId = field.removePrefix(PART)
            val at = chapters.indexOfFirst { it.first == partId }
            chapters.getOrNull(at)?.second?.takeIf { it.isNotBlank() }?.let { TextPlace(title = it) }
                ?: TextPlace(part = if (at >= 0) at + 1 else null)
        }
        else -> TextPlace(labelKey = I18nKeys.textLabel(s.id, field))
    }

    /**
     * The passages of the work [itemId] that name any of the values [of] among [names], as its
     * mentions are counted of the index ([SearchProvider.mentions]): its own words read again as they
     * were put in it — what is counted is what is shown — each a paragraph, or the part of a long one
     * around the names. None without a search plugin.
     */
    fun quotes(s: SourceStore, itemId: String, names: Map<String, List<String>>, of: Collection<String>): List<Quote> {
        val provider = plugins.search ?: return emptyList()
        val doc = documents(s, listOf(itemId), passages = false)[ItemKey(s.id, itemId)] ?: return emptyList()
        val places = provider.places(doc, names, of)
        val chapters = if (places.any { it.field.startsWith(PART) }) s.parts.titlesOf(listOf(itemId))[itemId].orEmpty() else emptyList()
        return places.flatMap { p -> Naming.quotesOf(placeOf(s, p.field, chapters), p.text, p.ranges) }
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
        updater.shutdownNow()
    }

    companion object {
        private const val CHUNK = 500

        /** The quiet after the last write of an item before the items written are indexed again: a page is sent several times as it grows. */
        private const val UPDATE_QUIET_MS = 2000L

        /** The field of the title. */
        private const val TITLE = "title"

        /** The fields of the facets: "facet.<facet key>". */
        private const val FACET = "facet."

        /** The fields of the chapters: "part:<part id>", searched as one field of the index, [PARTS]. */
        private const val PART = "part:"
        private const val PARTS = "parts"

        /** The field of the values of a facet the user and the model gave a work, beside the site's own. */
        private const val GIVEN = "+given"

        /**
         * The language a work's description is written in: its own facet of it
         * ([StandardLanguages.DESCRIPTION]); else, of a work of one language — a book — that one;
         * else none known.
         */
        fun descriptionLanguage(schema: SourceSchema, facets: Map<String, List<String>>): String? =
            facets[StandardLanguages.DESCRIPTION_KEY]?.firstOrNull()
                ?: schema.facet(StandardLanguages.KEY)?.takeIf { it.single }?.let { facets[it.key]?.singleOrNull() }
    }
}
