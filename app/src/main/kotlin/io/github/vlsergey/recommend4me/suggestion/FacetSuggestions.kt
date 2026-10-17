package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.part.PartsSaved
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.universe.CharacterSex
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.universe.Quote
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.search.ModelsRefreshing
import io.github.vlsergey.recommend4me.search.SearchProvider
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(FacetSuggestions::class.java)

/** The model gave items of a source other values of a facet it works out: what reads them reads them again. */
data class FacetChancesChanged(val source: String, val ids: List<String>)

/** The chances of works, made in memory when they are read ([FacetSuggestions.chances]): item to facet to value to chance. */
typealias WorkChances = Map<String, Map<String, Map<String, Chance>>>

/** [WorkChances] as they are made, a facet after another. */
private typealias MadeChances = HashMap<String, HashMap<String, Map<String, Chance>>>

/** The values of a facet an item lacks and more likely has than not, as the interface shows them. */
class ItemFacetSuggestions(val facet: FacetDef, val suggested: List<Chance>, val names: Map<String, String>)

/** A value a work may be given: the model's chance of it when it has one, how often the work's texts name it when counted. */
class FacetCandidate(val key: String, val chance: Double?, val mentions: Int?)

/** The values the user may yet give a work of one facet, the likeliest first, with their names. */
class ItemFacetCandidates(val facet: FacetDef, val candidates: List<FacetCandidate>, val names: Map<String, String>)

/**
 * WHAT THE MODEL SAYS OF THE VALUES OF FACETS, by the suggester plugin, for every facet a source
 * marks [suggest][FacetDef.suggest] or [infer][FacetDef.infer]: the chance of every value a work
 * has, and of every value it lacks that it more likely has than not.
 *
 * ONLY THE MODEL OF EACH FACET IS KEPT ([SuggestionRepository]): its fitted state and the values it
 * chooses from. THE CHANCES OF THE WORKS ARE NOT (user, 2026-10-15): whoever reads works — a page, a
 * card, a filter over the catalogue, the search index, the training of the grades — gets them made
 * in memory of those works alone, from their vectors and values, by the models kept ([chances]).
 * Nothing waits for a refit, nothing is written of a work.
 *
 * THE FACETS ARE WORKED OUT IN A CHAIN, in the order of [facetsOf]: each reads as its context the
 * values the site and the user give of every other facet, and the values the model gives of the
 * facets before it, made in the same pass — a work's characters are those of the universes given
 * before them, its pairings pairs of the characters given before them. The fitting and the reading
 * follow the same chain: a facet is judged on what it was fitted on.
 *
 * The models are fitted again in the background, in the queue of the computations, whenever
 * anything they learn from changed (the fingerprint of the whole facet); a fitting under way is
 * given up for a newer change and made again after it. Of the data nothing is kept between
 * operations; only the work waiting: the sources with changes, how many changes each had.
 */
@Service
class FacetSuggestions(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val settings: Settings,
    private val workload: Workload,
) {

    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "suggestions").apply { isDaemon = true } }
    private val scheduled = AtomicBoolean(false)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** How many changes each source has had ([changed]): a refresh begun before the last one is given up. */
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /**
     * The facets the model works on, in the order of the chain they are worked out in: those it
     * gives values of first — their values are the context of the others, the characters are of the
     * universes given before them, the pairings of the characters — in the order of the schema.
     */
    fun facetsOf(store: SourceStore): List<FacetDef> = store.schema.facets.filter { it.suggest || it.infer }.sortedBy { !it.infer }

    @EventListener
    fun itemsChanged(event: ItemsChanged) = changed(event.source, "items")

    @EventListener
    fun textVectorsChanged(event: TextVectorsChanged) = changed(event.source, "texts")

    @EventListener
    fun partsSaved(event: PartsSaved) = changed(event.source, "parts")

    /** The user's answer on a work's value: what the models of the facets learn from. */
    @EventListener
    fun answered(event: FacetAnswered) = changed(event.source, "answer")

    /**
     * Something the source's models learn from changed — the [reason], a key the interface words,
     * told in the queue of the works till the refresh begins: the source is refreshed after the
     * quiet that follows. A refresh under way is given up: it works on what it read before the
     * change, and the one after the quiet works on all of it.
     */
    private fun changed(source: String, reason: String) {
        val store = stores.source(source) ?: return
        if (facetsOf(store).isEmpty()) return
        generationOf(source).incrementAndGet()
        waiting += source
        val scheduling = scheduled.compareAndSet(false, true)
        val due = if (scheduling) Instant.now().plusSeconds(DELAY_SECONDS) else null
        facetsOf(store).forEach { workload.plan(refreshing(source, it), due, reason) }
        if (scheduling) worker.schedule(::drain, DELAY_SECONDS, TimeUnit.SECONDS)
    }

    /** The refresh of a facet of the source, as the queue of the works tells it planned and running. */
    private fun refreshing(source: String, facet: FacetDef) = Work("suggester", source = source, facet = facet.key)

    /** How many changes the source has had: a refresh begun before the last one is of stale data. */
    private fun generationOf(source: String): AtomicLong = generations.computeIfAbsent(source) { AtomicLong() }

    private fun drain() {
        scheduled.set(false)
        waiting.toList().forEach { source ->
            waiting -= source
            val store = stores.source(source) ?: return@forEach
            try {
                refresh(store, givenUp = true, null)
            } catch (e: CancellationException) {
                log.info("{}: chances given up for newer changes, made again after the quiet", source)
            } catch (e: Exception) {
                log.warn("{}: chances were not made: {}", source, e.message, e)
            }
        }
    }

    @Order(4)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // What was worked out of facets a source no longer has the model work on is forgotten
        stores.sources.forEach { s -> s.suggestions.keepOnly(facetsOf(s).map { it.key }) }
        worker.execute {
            stores.sources.filter { facetsOf(it).isNotEmpty() }.forEach { s ->
                try {
                    refresh(s, givenUp = true, mapOf("start" to 1))
                } catch (e: CancellationException) {
                    log.info("{}: chances given up on start for newer changes, made again after the quiet", s.id)
                } catch (e: Exception) {
                    log.error("{}: chances were not made on start", s.id, e)
                }
            }
        }
    }

    /**
     * Fits the model of every facet of the source again when what it learns from changed, down the
     * chain. For a caller that waits for it: carried through whatever changes meanwhile; those are
     * refreshed after their quiet.
     */
    fun refresh(store: SourceStore) = refresh(store, givenUp = false, mapOf("asked" to 1))

    /**
     * [refresh]; [givenUp] — with a [CancellationException] once the source changes again
     * ([changed]): the background's; [reasons] — what it is made for, told in the queue of the works
     * beside those every facet was planned for ([changed]); null — those alone.
     *
     * Every facet a work of the queue of its own; the chances of every work of the facets before,
     * made in the pass, are the context of the next — held for the pass alone. The works whose given
     * values a refit changed are told once at its end ([FacetChancesChanged]).
     */
    private fun refresh(store: SourceStore, givenUp: Boolean, reasons: Map<String, Int>?) {
        val encoder = plugins.textEncoder() ?: return
        // What the models count of the index — the names the works' words name — of the works as they are now
        events.publishEvent(ModelsRefreshing(store.id))
        val generation = generationOf(store.id).get()
        val stop = if (givenUp) ({ generationOf(store.id).get() != generation }) else ({ false })
        val facets = facetsOf(store)
        val made = seeded(store, null)
        val changedIds = LinkedHashSet<String>()
        facets.forEachIndexed { k, facet ->
            val work = refreshing(store.id, facet)
            val planned = workload.take(work)
            val suggester = plugins.suggester(store.id, facet.key) ?: return@forEachIndexed
            workload.run(work, reasons.orEmpty().toMutableMap().apply { planned.forEach { (key, n) -> merge(key, n, Int::plus) } }) {
                lockOf(store, facet).withLock { changedIds += fitFacet(store, facets, k, suggester, encoder, made, stop) }
            }
        }
        if (changedIds.isNotEmpty()) events.publishEvent(FacetChancesChanged(store.id, changedIds.toList()))
    }

    /**
     * The facet's suggester fitted again FROM SCRATCH — every setting tried anew, not a short walk on
     * from the state fitted last: the user asked for it on the page of the models. Its context, the
     * facets before it in the chain, made of every work first. In the background; false when the
     * source has no such facet.
     */
    fun searchAnew(store: SourceStore, facetKey: String): Boolean {
        val facets = facetsOf(store)
        val k = facets.indexOfFirst { it.key == facetKey }.takeIf { it >= 0 } ?: return false
        val facet = facets[k]
        if (plugins.suggester(store.id, facet.key) == null) return false
        val encoder = plugins.textEncoder() ?: return false
        val id = "${store.id}/${facet.key}"
        searching += id
        worker.execute {
            try {
                // The suggester named when it runs: one chosen meanwhile is the one fitted
                val suggester = plugins.suggester(store.id, facet.key) ?: return@execute
                val changedIds = workload.run(Work("suggesterAnew", source = store.id, facet = facet.key), mapOf("asked" to 1)) {
                    lockOf(store, facet).withLock { fitFacet(store, facets, k, suggester, encoder, chain(store, null, k), { false }, anew = true) }
                }
                if (changedIds.isNotEmpty()) events.publishEvent(FacetChancesChanged(store.id, changedIds))
            } catch (e: Exception) {
                log.warn("{}: the suggester of {} was not fitted anew: {}", store.id, facet.key, e.message, e)
            } finally {
                searching -= id
            }
        }
        return true
    }

    /**
     * The suggester [method] made the one the facet learns with, and the facet fitted with it from
     * scratch ([searchAnew]); false when there is no such suggester or facet.
     */
    fun chooseMethod(store: SourceStore, facetKey: String, method: String): Boolean {
        if (plugins.suggesters.none { it.id == method } || facetsOf(store).none { it.key == facetKey }) return false
        settings.set(Plugins.suggesterSetting(store.id, facetKey), method)
        return searchAnew(store, facetKey)
    }

    /**
     * Everything the suggesters of the facet learn from, read now: what a comparison of them fits
     * every one on ([FacetComparisons]); null when the source has no such facet or nothing to read with.
     */
    fun task(store: SourceStore, facetKey: String): SuggestionTask? {
        val facets = facetsOf(store)
        val k = facets.indexOfFirst { it.key == facetKey }.takeIf { it >= 0 } ?: return null
        val encoder = plugins.textEncoder() ?: return null
        val made = chain(store, null, k)
        return load(store, facets[k], encoder, vocabularyOf(store, facets[k], null, made), null, made, null).task
    }

    /** The facets being fitted anew now ([searchAnew]), by "source/facet": the work under way, nothing of the data. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether the facet's suggester is being fitted anew ([searchAnew]). */
    fun searching(store: SourceStore, facetKey: String): Boolean = "${store.id}/$facetKey" in searching

    /** One lock a facet: a fitting of one facet does not wait for a long fitting of another. */
    private fun lockOf(store: SourceStore, facet: FacetDef) = locks.computeIfAbsent("${store.id}/${facet.key}") { ReentrantLock() }

    /**
     * THE CHANCES OF THE WORKS [ids] — of every work of the source when null — made now in memory
     * by the models kept, down the chain of the facets; of the facets up to [through] alone when
     * given (none when the source works out no such facet). Of every facet the values shown: had,
     * rejected, given as examples, likely, and the likeliest others offered; the drafts of the layer
     * among them ([Chance.draftedFrom]). A facet never fitted has no chances, its drafts alone.
     * Nothing is written, nothing waited for: never in the queue of the computations — the marking
     * of a work waits for nothing (user, 2026-10-15).
     */
    fun chances(store: SourceStore, ids: Collection<String>?, through: String? = null): WorkChances {
        val facets = facetsOf(store)
        val upTo = if (through == null) facets.size else facets.indexOfFirst { it.key == through } + 1
        return chain(store, ids, upTo)
    }

    /** [chances] of the [wanted] facets alone — those on a card, those the model of the grades reads: the chain down to the last of them. */
    fun chancesOf(store: SourceStore, ids: Collection<String>?, wanted: Collection<String>): WorkChances =
        facetsOf(store).lastOrNull { it.key in wanted }?.let { chances(store, ids, it.key) } ?: seeded(store, ids)

    /** The chances of the works [ids] (every work when null) of the first [upTo] facets of the chain. */
    private fun chain(store: SourceStore, ids: Collection<String>?, upTo: Int): MadeChances {
        val made = seeded(store, ids)
        val encoder = plugins.textEncoder() ?: return made
        if (ids != null && ids.isEmpty()) return made
        val facets = facetsOf(store)
        for (k in 0 until minOf(upTo, facets.size)) {
            val facet = facets[k]
            val suggester = plugins.suggester(store.id, facet.key) ?: continue
            val scored = scored(store, facet, suggester, encoder, ids, made) ?: continue
            keep(made, facet, scored.data, scored.scores, scored.vocabulary)
        }
        return made
    }

    /** The chances of a facet's works as the model scores them: the task they are of, its scores, the vocabulary read. */
    private class Scored(val data: FacetData, val scores: SuggestionScores, val vocabulary: Vocabulary?)

    /**
     * THE FACET'S CHANCES OF THE WORKS [ids] (every work when null), by the model kept. By its scoring
     * kept apart ([KeptScoring]) where there is one: the works' own features read, their texts' counts
     * of the values' names as the fitting counted them ([SuggestionRepository.mentions]), the values
     * those of the scoring — no dictionary, no name of any value (user, 2026-10-16): a page of twenty
     * works read every character's name and the model whole, and a few of them at once ran the heap
     * out. Else — of a suggester without one, of a model fitted before scorings were kept, till the
     * next refresh keeps it — on the whole task. Null when the facet was never fitted.
     */
    private fun scored(store: SourceStore, facet: FacetDef, suggester: FacetSuggester, encoder: TextEncoder, ids: Collection<String>?, made: WorkChances): Scored? {
        store.suggestions.scoring(facet.key)?.takeIf { it.suggester == suggester.id }?.let { kept ->
            val vocabulary = vocabularyOf(store, facet, ids, made, light = true)
            // The names counted of the index, as the fitting counted them
            val mentions = vocabulary?.mentions(store, ids, stores.typeOf(store.id).id, plugins.search)
            val data = FacetData.load(
                store, facet, encoder, imageEncoder = plugins.imageEncoder()?.id, sets = stores.typeOf(store.id).embeddings.ids(), only = ids,
                modelled = made, mentions = mentions, allowed = vocabulary?.allowed, examples = vocabulary?.examples, kept = kept.values,
            )
            if (data.values.isEmpty()) return null
            return suggester.scoring(kept.bytes, data.task)?.let { Scored(data, it, vocabulary) }
        }
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id } ?: return null
        val vocabulary = vocabularyOf(store, facet, ids, made)
        val data = load(store, facet, encoder, vocabulary, ids, made, stored.values)
        if (data.values.isEmpty()) return null
        val fitted = suggester.unpack(stored.content, data.task) ?: return null
        return Scored(data, fitted.on(data.task), vocabulary)
    }

    /** The drafts of the layer of the works [ids] (every work when null) as their chances: 1, marked so, till the model gives its own. */
    private fun seeded(store: SourceStore, ids: Collection<String>?): MadeChances {
        val made = MadeChances()
        (if (ids == null) store.drafts.all() else store.drafts.ofItems(ids)).forEach { (id, list) ->
            list.groupBy { it.facet }.forEach { (facet, drafts) ->
                made.getOrPut(id) { HashMap() }[facet] = drafts.associate { d -> d.key to Chance(id, facet, d.key, had = false, chance = 1.0, draftedFrom = d.from) }
            }
        }
        return made
    }

    /**
     * The chances of the [facet] of every work of [data] put among [made] — over the drafts, which
     * keep the model's chance — and the values the model alone gives each (no draft among them):
     * what a refit compares.
     */
    private fun keep(made: MadeChances, facet: FacetDef, data: FacetData, scores: SuggestionScores, vocabulary: Vocabulary?): Map<String, Set<String>> {
        val given = HashMap<String, Set<String>>()
        data.ids.indices.chunked(CHUNK).forEach { chunk ->
            val chances = scores.of(chunk.toIntArray())
            chunk.forEachIndexed { j, i ->
                val id = data.ids[i]
                val own = chancesOf(id, facet.key, chances[j], data, i, vocabulary?.allowed?.invoke(id)).associateBy { it.key }
                if (facet.infer) given[id] = ModelValues.given(own).toSet()
                val byFacet = made.getOrPut(id) { HashMap() }
                val drafts = byFacet[facet.key].orEmpty().values.filter { it.drafted }
                byFacet[facet.key] = if (drafts.isEmpty()) own else HashMap(own).also { out ->
                    drafts.forEach { d -> out[d.key] = out[d.key]?.copy(draftedFrom = d.draftedFrom) ?: d }
                }
            }
        }
        return given
    }

    /**
     * The vocabulary of the facet: of the universes, or of a facet learnt from the sites' facet; null
     * of any other. [light]: what scoring the works by the scoring kept needs alone — the values each
     * may have, the site's examples — no name of any value, no group: the scoring knows them.
     */
    private fun vocabularyOf(store: SourceStore, facet: FacetDef, ids: Collection<String>?, made: WorkChances, light: Boolean = false): Vocabulary? =
        universeVocabulary(store, facet, ids, made, light) ?: examplesVocabulary(store, facet, ids)

    /**
     * What the model of the facet reads — of the works [ids] alone, when given, judged among the
     * values the model kept chooses from ([valueKeys]) — with the values the model gives of the
     * [context] facets, [made] before it in the chain.
     */
    private fun load(
        store: SourceStore, facet: FacetDef, encoder: TextEncoder, vocabulary: Vocabulary?, ids: Collection<String>?,
        made: WorkChances, valueKeys: List<String>?,
    ) = FacetData.load(
        store, facet, encoder, vocabulary?.values.orEmpty(),
        imageEncoder = plugins.imageEncoder()?.id, sets = stores.typeOf(store.id).embeddings.ids(), only = ids,
        modelled = made, valueKeys = valueKeys,
        // Counted in the texts of the works [ids] alone: no other work's chances are made
        mentions = vocabulary?.mentions(store, ids, stores.typeOf(store.id).id, plugins.search),
        allowed = vocabulary?.allowed, groupings = vocabulary?.groupings, examples = vocabulary?.examples, lackWeight = vocabulary?.lackWeight ?: 1.0,
        hintNames = vocabulary?.hintNames.orEmpty(), hintAmong = vocabulary?.hintAmong ?: { emptyList() },
    )

    /**
     * The model of the [k]-th of the [facets] fitted again when what it learns from changed — every
     * setting tried anew when [anew], the user asked for it ([searchAnew]) — and every work's chances
     * of it made into [made], the context of the facets after it. [stop] is asked between the
     * stages, and by the fitting between its steps: newer data came, and the work is given up with a
     * [CancellationException] before anything made of the old is kept. Answers the works whose
     * given values a refit changed: every work scored by the model before and after it.
     */
    private fun fitFacet(
        store: SourceStore, facets: List<FacetDef>, k: Int, suggester: FacetSuggester, encoder: TextEncoder, made: MadeChances, stop: () -> Boolean,
        anew: Boolean = false,
    ): List<String> {
        val facet = facets[k]
        fun checkpoint() {
            if (stop()) throw CancellationException("${store.id}: ${facet.key} given up for newer changes")
        }
        val started = System.currentTimeMillis()
        val vocabulary = vocabularyOf(store, facet, null, made)
        val vocabularyMillis = System.currentTimeMillis() - started
        checkpoint()
        val data = load(store, facet, encoder, vocabulary, null, made, null)
        val read = System.currentTimeMillis() - started
        checkpoint()
        // The whole task kept in a file when asked for: its fitting made again and measured apart from the application
        System.getenv(TASKS_FOLDER)?.let { dir -> SuggestionTaskFile.write(data.task, Path.of(dir, "${store.id}-${facet.key}.task.gz")) }
        if (data.values.isEmpty()) return emptyList()
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id }
        val kept = stored?.let { suggester.unpack(it.content, data.task) }
        val unpacked = System.currentTimeMillis() - started
        checkpoint()
        val current = !anew && kept != null && stored.basis == data.basis
        val fitted = if (current) {
            // Fitted before the values it chooses from, or its scoring, were kept: kept now, not fitted again
            if (stored.values != data.values || !store.suggestions.hasScoring(facet.key)) store.suggestions.keepValues(facet.key, data.values, kept.scoring(data.task))
            kept
        } else {
            // From the state fitted last when there is one: the data moved a little since
            val fitStarted = System.currentTimeMillis()
            val fresh = suggester.fit(data.task, if (anew) null else kept, stop) ?: return emptyList()
            checkpoint()
            val fit = SuggesterFit(fresh.report, System.currentTimeMillis() - fitStarted, data.ids.size, data.values.size)
            store.suggestions.saveSuggester(facet.key, suggester.id, Instant.now(), data.basis, fresh.pack(), data.values, fresh.scoring(data.task), fit)
            log.info("{}: the suggester of {} fitted on {} items, {} values in {} ms, {} of them reading", store.id, facet.key, data.ids.size, data.values.size, System.currentTimeMillis() - started, read)
            fresh
        }
        val fittedAt = System.currentTimeMillis() - started
        val after = keep(made, facet, data, fitted.on(data.task), vocabulary)
        val scored = System.currentTimeMillis() - started
        // What the model before the refit gave alone: scored again, nothing of it kept
        val changedIds = if (current || !facet.infer) emptyList() else {
            val before = kept?.let { k0 -> keep(MadeChances(), facet, data, k0.on(data.task), vocabulary) }
            data.ids.filter { id -> before == null || before[id].orEmpty() != after[id].orEmpty() }
        }
        log.info(
            "{}: chances of {} of {} items made in memory in {} ms — the dictionary {}, reading {}, the weights {}, fitting {}, scoring {}; {} works given other values",
            store.id, facet.key, data.ids.size, System.currentTimeMillis() - started,
            vocabularyMillis, read - vocabularyMillis, unpacked - read, fittedAt - unpacked, scored - fittedAt, changedIds.size,
        )
        return changedIds
    }

    /**
     * What is made of item [i]'s chances: of every value it has, of every value the user rejected
     * (shown crossed out, in its place by its chance), every one the site gives it as an example —
     * offered to answer on whatever its chance, shown with it — and of the values it lacks those it
     * more likely has than not and the likeliest others offered besides; of a facet that says which
     * values a work may have ([allowed]: a work's characters are of its universes, its pairings of
     * its characters) of those alone. Not every value it may have: of a work of a large universe
     * thousands of characters — the user choosing among them gets every one at the asking ([candidates]).
     */
    private fun chancesOf(id: String, facet: String, chances: FloatArray, data: FacetData, i: Int, allowed: Set<String>?): List<Chance> {
        val has = data.held[i].toHashSet()
        val rejected = data.task.rejected[i].toHashSet()
        val named = data.task.mentions[i]
        fun chance(v: Int, had: Boolean) = Chance(id, facet, data.values[v], had, chances[v].toDouble(), named?.let { it[v] ?: 0 })
        // All named by the user's word: the item has its own values and is offered no other
        if (data.complete[i]) return has.map { chance(it, true) }
        fun may(v: Int) = allowed == null || data.values[v] in allowed
        val examples = data.task.examples[i].toHashSet()
        // The likeliest of the rest too, likely or not, to offer the work at least so many the site does not
        // give ([ModelValues.offered]): chosen of the values by their places, not of a map of thousands of
        // characters made for every work and sorted whole
        fun open(v: Int) = v !in has && v !in rejected && v !in examples && may(v)
        var shown = 0
        for (v in chances.indices) if (open(v) && chances[v] > ModelValues.LIKELY) shown++
        val wanted = (ModelValues.OFFERED - shown).coerceAtLeast(0)
        val offered = HashSet<Int>()
        repeat(wanted) {
            var best = -1
            for (v in chances.indices) {
                if (!open(v) || chances[v] > ModelValues.LIKELY || v in offered) continue
                if (best < 0 || chances[v] > chances[best]) best = v
            }
            if (best >= 0) offered += best
        }
        return chances.indices.mapNotNull { v ->
            when {
                v in has -> chance(v, true)
                // The site's own: shown to answer on, unlikely as it may be
                v in examples -> chance(v, false)
                // A value the user rejected keeps its chance: it is shown crossed out, in its place by it
                v in rejected -> chance(v, false)
                may(v) && (chances[v] > ModelValues.LIKELY || v in offered) -> chance(v, false)
                else -> null
            }
        }
    }

    /**
     * The values of a facet of the universes besides the items' own, with the text their names are
     * encoded from, and the values each item may be given; null for any other facet.
     */
    private class Vocabulary(
        val values: Map<String, String>,
        val allowed: ((String) -> Set<String>)?,
        /**
         * The names every work's own words are looked for by in the index ([SearchProvider.mentions]):
         * value to the names it goes by — one map for every work of the same, the works of one
         * universe sharing it; null for a work not counted. [valuesOf]: the values a name found is of.
         */
        val names: ((String) -> Map<String, List<String>>?)? = null,
        val valuesOf: (String) -> Collection<String> = { listOf(it) },
        /** Of the pairings: the [names] are of characters, a value two of them named in one paragraph ([SearchProvider.together]). */
        val paired: Boolean = false,
        /** The groupings of the values the task is made of, in its order; none when they fall into no known groups. */
        val groupings: (List<String>) -> List<ValueGrouping> = { emptyList() },
        /** The values the site gives each item as examples of the facet ([FacetDef.examplesFrom]); null for a facet without. */
        val examples: ((String) -> List<String>)? = null,
        /** How much a value an item lacks counts as one it has not ([SuggestionTask.lackWeight]). */
        val lackWeight: Double = 1.0,
        /** Every name each value goes by, each a text that names it ([NameHint]); none for a facet whose values are not named in texts. */
        val hintNames: Map<String, List<String>> = emptyMap(),
        /**
         * The values a text naming a value is judged among: the value and its rivals — the characters
         * of its universes. One collection for every value judged among the same: it is turned into
         * the task's indices once ([NameHint.among]).
         */
        val hintAmong: (String) -> Collection<String> = { emptyList() },
    ) {
        /**
         * How often each item's own words name each value, by [names], of the index of the [search] —
         * a call for every map of names shared by works; of the pairings, in how many paragraphs two
         * characters are named together. Null when the values are not counted.
         */
        fun mentions(store: SourceStore, ids: Collection<String>?, typeId: String, search: SearchProvider?): ((String) -> Map<String, Int>?)? {
            names?.let { of ->
                val provider = search ?: return null
                val wanted = ids ?: store.items.keys().map { it.id }
                val groups = IdentityHashMap<Map<String, List<String>>, MutableList<String>>()
                wanted.forEach { id -> of(id)?.let { groups.getOrPut(it) { ArrayList() } += id } }
                val counted = HashMap<String, Map<String, Int>>()
                groups.forEach { (named, works) ->
                    // Every work of the source when all of them are: the index read whole, not a work at a time
                    val keys = if (ids == null && groups.size == 1) null else works.map { ItemKey(store.id, it) }
                    if (paired) provider.together(typeId, keys, named).forEach { (key, pairs) ->
                        if (key.source == store.id) counted[key.id] = pairs.mapKeys { (pair, _) -> UniverseFacet.pairing(pair.first, pair.second) }
                    } else provider.mentions(typeId, keys, named).forEach { (key, byName) ->
                        if (key.source != store.id) return@forEach
                        val byValue = HashMap<String, Int>()
                        byName.forEach { (name, n) -> valuesOf(name).forEach { byValue.merge(it, n, Int::plus) } }
                        counted[key.id] = byValue
                    }
                }
                val within = ids?.toHashSet()
                // A work counted that names none has none, not an unknown count
                return { id -> if (within != null && id !in within || of(id) == null) null else counted[id].orEmpty() }
            }
            return null
        }

    }

    /**
     * The values of a facet of the layer learnt from a facet of the sites ([FacetDef.examplesFrom]):
     * every value of that facet of every source of the type, one by its name — "Магия" of one site
     * and of another one tag — and every item's own values of it as its examples; null for any
     * other facet.
     *
     * A value the site lacks says little of the work: authors leave out most tags that fit. How
     * little, the user's answers tell — of the values the site did not give a work, the share the
     * user rejected rather than confirmed, by the rule of succession: as much as a lacking value
     * weighs as a value the work has not.
     *
     * Of the works [ids] alone, when given — read by the model kept, which knows its values — their
     * examples alone: the values of every source are what a fitting learns from.
     */
    private fun examplesVocabulary(store: SourceStore, facet: FacetDef, ids: Collection<String>?): Vocabulary? {
        val from = store.schema.facets.firstOrNull { it.key == facet.examplesFrom } ?: return null
        if (ids != null) {
            val bound = store.tags.bindingsOf(store.id)
            val own = store.items.facetsOf(ids).mapValues { (_, facets) -> facets[from.key].orEmpty().mapNotNull { bound[it]?.let(TagRepository::key) }.distinct() }
            return Vocabulary(emptyMap(), allowed = null, examples = { id -> own[id].orEmpty() })
        }
        // Every normalized tag a site's tag of the type is bound to, of every source of the type
        val bindings = store.tags.bindings()
        val values = HashMap<String, String>()
        stores.typeOf(store.id).sources.forEach { s ->
            val theirs = s.schema.facets.firstOrNull { it.standard != null && it.standard === from.standard } ?: return@forEach
            val names = s.names(theirs.key)
            s.items.valueUses(theirs.key).keys.forEach { key ->
                bindings[SiteTagRef(s.id, key)]?.let { values.putIfAbsent(TagRepository.key(it), (names[key] ?: key).trim()) }
            }
        }
        // A tag is read by its name as the user and the sites name it ([TagRepository])
        store.tags.shown(values.keys).forEach { (key, name) -> values[key] = name }
        val bound = store.tags.bindingsOf(store.id)
        val own = HashMap<String, List<String>>()
        store.items.forEachFacet(from.key) { id, keys -> own[id] = keys.mapNotNull { bound[it]?.let(TagRepository::key) }.distinct() }
        val answered = store.corrections.allFacets().filter { it.facet == facet.key && it.key !in own[it.itemId].orEmpty() }
        val denied = answered.count { !it.added }
        return Vocabulary(
            values, allowed = null,
            examples = { id -> own[id].orEmpty() },
            lackWeight = (denied + 1.0) / (answered.size + 2.0),
        )
    }

    /**
     * The vocabulary of a facet of the universes; of the works [ids], when given, what depends on the
     * works read of them alone — their universes, the characters of their other facet, their pairs —
     * not of every work: of a work of a large universe the chances of thousands of characters each.
     * The values the model gave of the facets before it are [made] in the chain.
     */
    private fun universeVocabulary(store: SourceStore, facet: FacetDef, ids: Collection<String>?, made: WorkChances, light: Boolean = false): Vocabulary? {
        val type = stores.typeOf(store.id)
        if (!type.type.universes) return null
        return when (facet.key) {
            // The kinds, the universes, the characters and the pairings are named by the dictionary, whatever the source ([UniverseNames])
            UniverseFacets.KIND -> Vocabulary(UniverseFacet.KINDS, null)
            // Every universe of the dictionary, linked to a work or not yet — to a work not known to have none
            UniverseFacets.UNIVERSE -> {
                val kinds = HashMap<String, List<String>>()
                if (ids == null) store.items.forEachFacet(UniverseFacets.KIND) { id, keys -> kinds[id] = keys }
                else store.items.facetsOf(ids).forEach { (id, facets) -> facets[UniverseFacets.KIND]?.let { kinds[id] = it } }
                answersOf(store, ids).filter { it.facet == UniverseFacets.KIND }.groupBy { it.itemId }.forEach { (id, own) ->
                    kinds[id] = Corrected.facets(mapOf(UniverseFacets.KIND to kinds[id].orEmpty()), own)[UniverseFacets.KIND].orEmpty()
                }
                // Realism, an alternative history and an original work: our world, ours changed, or a world of its own
                fun original(id: String) = kinds[id].orEmpty().let { it.isNotEmpty() && it.all { k -> k in UniverseFacets.UNIVERSELESS } }
                // NO NAME OF IT LOOKED FOR IN THE WORK'S WORDS (user, 2026-10-16): the universe is told by the
                // work's texts, its tags and its other values; the names below are what the fitting learns from.
                // Of the works read, the universes' keys alone: not every universe's characters read to count them
                if (light) {
                    val keys = type.universes.keys().map { it.value }.toSet()
                    return Vocabulary(emptyMap(), allowed = { id -> if (original(id)) emptySet() else keys })
                }
                val universes = type.universes.all()
                val all = universes.associate { it.value to it.name }
                // A universe is named by its characters ("Гарри", "Хогвартс") and by its own name
                val ofCharacter = HashMap<String, MutableSet<String>>()
                val names = HashMap<String, List<String>>()
                universes.forEach { u ->
                    names[u.value] = u.names
                    type.universes.characters(u.catalogue, u.id).forEach { c ->
                        val value = UniverseFacet.valueOf(u.catalogue, c.id)
                        names[value] = c.names
                        ofCharacter.getOrPut(value) { HashSet() } += u.value
                    }
                }
                // Every name of a universe and of each of its characters is a text that names it, and no other universe
                val hintNames = HashMap<String, MutableList<String>>()
                names.forEach { (value, own) -> (ofCharacter[value] ?: setOf(value).filter { it in all }).forEach { u -> hintNames.getOrPut(u) { ArrayList() } += own } }
                Vocabulary(
                    all,
                    allowed = { id -> if (original(id)) emptySet() else all.keys },
                    hintNames = hintNames.mapValues { it.value.distinct() },
                    hintAmong = { all.keys },
                )
            }
            // The main characters and the secondary ones alike, each without what the other has
            UniverseFacets.CHARACTERS, UniverseFacets.SECONDARY_CHARACTERS -> {
                // Every character of every universe — by all its names: the fans write the one they like —
                // and the original characters; a work is given those of its own universes. Of the works
                // read, the characters of their universes alone: no other is chosen from ([FacetData.load])
                val universes = workUniverses(store, ids, made)
                // The universes by their keys alone: their characters read below, of those wanted
                val read = type.universes.keys().let { all ->
                    if (ids == null) all else ids.flatMap { universes[it].orEmpty() }.toSet().let { own -> all.filter { it.value in own } }
                }
                val ofUniverse = HashMap<String, Set<String>>()
                val values = HashMap<String, String>(UniverseFacet.ORIGINAL_CHARACTERS)
                val names = HashMap<String, List<String>>()
                // Their sexes of the same reading: a universe's characters read once
                val sexes = HashMap<String, CharacterSex>(UniverseFacet.ORIGINAL_SEXES)
                read.forEach { u ->
                    val characters = type.universes.characters(u.catalogue, u.id)
                    ofUniverse[u.value] = characters.map { UniverseFacet.valueOf(u.catalogue, it.id) }.toSet()
                    characters.forEach { c ->
                        val value = UniverseFacet.valueOf(u.catalogue, c.id)
                        values[value] = c.names.joinToString(" / ")
                        names[value] = c.names
                        c.sex?.let { sexes[value] = it }
                    }
                }
                val out = leftOut(store, facet.key, ids, made)
                fun of(id: String) = universes[id].orEmpty().flatMap { ofUniverse[it].orEmpty() }.toSet() - out(id)
                // The characters of a work's universes, looked for in its words: a work of none is not counted.
                // One map of names for every work of the same characters, the index read once for them all
                val nameSets = HashMap<Set<String>, Map<String, List<String>>>()
                val named = { id: String -> of(id).takeIf { it.isNotEmpty() }?.let { own -> nameSets.getOrPut(own) { own.associateWith { names[it].orEmpty() } } } }
                if (light) return Vocabulary(emptyMap(), allowed = { id -> of(id) + UniverseFacet.ORIGINAL_CHARACTERS.keys }, names = named)
                // A name of a character names it and none of the other characters of its universes:
                // judged among them all, one set for every character of the same universes
                val universesOf = HashMap<String, MutableSet<String>>()
                ofUniverse.forEach { (u, members) -> members.forEach { universesOf.getOrPut(it) { HashSet() } += u } }
                val among = HashMap<Set<String>, Set<String>>()
                universesOf.values.forEach { us -> among.getOrPut(us) { us.flatMap { ofUniverse[it].orEmpty() }.toSet() } }
                Vocabulary(
                    values,
                    hintNames = names,
                    hintAmong = { c -> universesOf[c]?.let(among::getValue).orEmpty() },
                    allowed = { id -> of(id) + UniverseFacet.ORIGINAL_CHARACTERS.keys },
                    names = named,
                    // A work's tags tell the sex of its main characters
                    groupings = { list -> listOf(ValueGrouping("sex", CharacterSex.entries.map { it.name }, list.map { c -> sexes[c]?.let { intArrayOf(it.ordinal) } ?: IntArray(0) })) },
                )
            }
            UniverseFacets.PAIRINGS -> {
                // Every pair of a work's characters: the confirmed ones and those the model gives it
                val characters = linked(store, UniverseFacets.CHARACTERS, ids).toMutableMap()
                made.forEach { (id, chances) ->
                    val given = ModelValues.given(chances[UniverseFacets.CHARACTERS].orEmpty())
                    if (given.isNotEmpty()) characters[id] = characters[id].orEmpty() + given
                }
                val rejected = answersOf(store, ids).filter { it.facet == UniverseFacets.CHARACTERS && !it.added }
                    .groupBy({ it.itemId }, { it.key })
                // The original characters pair with anyone: a work need not list them among its main ones
                val pairs = characters.mapValues { (id, own) ->
                    val kept = (own + UniverseFacet.ORIGINAL_CHARACTERS.keys - rejected[id].orEmpty().toSet()).distinct().sorted()
                    kept.flatMapIndexed { k, a -> kept.drop(k + 1).map { b -> UniverseFacet.pairing(a, b) } }.toSet()
                }
                // The names of the characters of the works' universes, looked for in their words: two named in one
                // paragraph. The original characters have no names to count; one map for every work of the same characters
                val named = pairedNames(store, ids, made, characters)
                if (light) return Vocabulary(emptyMap(), allowed = { id -> pairs[id].orEmpty() }, names = named, paired = true)
                val names = UniverseFacet.ORIGINAL_CHARACTERS +
                    store.names(UniverseFacets.CHARACTERS, pairs.values.flatten().flatMap { p -> UniverseFacet.members(p)?.toList().orEmpty() })
                val values = pairs.values.flatten().toSet().associateWith { p ->
                    UniverseFacet.members(p)!!.let { (a, b) -> "${names[a] ?: a} / ${names[b] ?: b}" }
                }
                // Their names alone are what the model reads; shown with the sign of their characters' sexes ([UniverseNames])
                val sexes = UniverseFacet.sexes(type.universes)
                // The sexes of a pairing's two characters, and whether they are one sex: two groupings
                val kinds = CharacterSex.entries.flatMap { x -> CharacterSex.entries.filter { it >= x }.map { x to it } }
                // A pairing of two characters of the dictionary is named "A / B"; it names no other pairing of either of them
                val ofMember = HashMap<String, MutableSet<String>>()
                values.keys.forEach { p -> UniverseFacet.members(p)?.toList()?.forEach { ofMember.getOrPut(it) { HashSet() } += p } }
                val ofTwoKnown = values.filterKeys { p -> UniverseFacet.members(p)?.toList()?.none { it in UniverseFacet.ORIGINAL_CHARACTERS } == true }
                Vocabulary(
                    values,
                    hintNames = ofTwoKnown.mapValues { listOf(it.value) },
                    // Its own among them: a pairing is one of each of its characters'
                    hintAmong = { p -> UniverseFacet.members(p)?.toList().orEmpty().flatMap { ofMember[it].orEmpty() }.toSet() },
                    allowed = { id -> pairs[id].orEmpty() },
                    names = named, paired = true,
                    groupings = { list ->
                        val of = list.map { UniverseFacet.sexesOf(it, sexes) }
                        fun one(group: Int?) = group?.let { intArrayOf(it) } ?: IntArray(0)
                        // A pairing is in the groups of its two characters: what is learnt of a
                        // character's pairings holds for a pairing of it never seen with a work
                        val members = list.map { UniverseFacet.members(it)?.toList().orEmpty() }
                        val characters = members.flatten().distinct().sorted()
                        val at = characters.withIndex().associate { (i, c) -> c to i }
                        listOf(
                            ValueGrouping("sexes", kinds.map { (x, y) -> "$x+$y" }, of.map { one(it?.let(kinds::indexOf)) }),
                            ValueGrouping("one sex", listOf("same", "different"), of.map { s ->
                                one(s?.takeIf { (x, y) -> x != CharacterSex.OTHER && y != CharacterSex.OTHER }?.let { (x, y) -> if (x == y) 0 else 1 })
                            }),
                            ValueGrouping("characters", characters, members.map { m -> m.map { at.getValue(it) }.toIntArray() }),
                        )
                    },
                )
            }
            else -> null
        }
    }

    /**
     * The names of every work's characters [characters] — of the universes of the works [ids], every
     * work when null — a map for every work of two characters and more besides the original ones, one
     * map for every work of the same: what two of them named in one paragraph are looked for by.
     */
    private fun pairedNames(store: SourceStore, ids: Collection<String>?, made: WorkChances, characters: Map<String, List<String>>): (String) -> Map<String, List<String>>? {
        val type = stores.typeOf(store.id)
        val universes = workUniverses(store, ids, made)
        val own = universes.values.flatten().toSet()
        val known = HashMap<String, List<String>>()
        type.universes.keys().filter { it.value in own }.forEach { u ->
            type.universes.characters(u.catalogue, u.id).forEach { known[UniverseFacet.valueOf(u.catalogue, it.id)] = it.names }
        }
        val missing = characters.values.flatten().toSet().filter { it !in known && it !in UniverseFacet.ORIGINAL_CHARACTERS }
        val shown = store.names(UniverseFacets.CHARACTERS, missing)
        val sets = HashMap<Set<String>, Map<String, List<String>>>()
        return { id ->
            val named = characters[id].orEmpty().toSet() - UniverseFacet.ORIGINAL_CHARACTERS.keys
            if (named.size < 2) null else sets.getOrPut(named) { named.associateWith { known[it] ?: listOfNotNull(shown[it]) } }
        }
    }

    /**
     * The characters left out of the [facet] of every item ([UniverseFacet.leftOut]): those of its
     * other facet of characters — the user's answers, and the main ones the model gives it. The
     * main characters are worked out before the secondary ones in the chain: [made] has them.
     */
    private fun leftOut(store: SourceStore, facet: String, ids: Collection<String>?, made: WorkChances): (String) -> Set<String> {
        if (UniverseFacet.otherCharacters(facet) == null) return { emptySet() }
        val answers = answersOf(store, ids).filter { it.facet in UniverseFacet.CHARACTER_FACETS }.groupBy { it.itemId }
        val given = if (facet == UniverseFacets.SECONDARY_CHARACTERS)
            made.mapValues { ModelValues.given(it.value[UniverseFacets.CHARACTERS].orEmpty()) }
        else emptyMap()
        return { id ->
            val own = answers[id].orEmpty()
            fun said(f: String) = own.filter { it.facet == f && it.added }.map { it.key }
            fun has(f: String) =
                ((if (f == UniverseFacets.CHARACTERS) given[id] else null).orEmpty() + said(f)).toSet() - own.filter { it.facet == f && !it.added }.map { it.key }.toSet()
            UniverseFacet.leftOut(facet, ::has, ::said)
        }
    }

    /**
     * The universes whose characters every work may have: the ones the user confirmed, and the ones
     * the model gives it — more likely than not — unless the user rejected them; none but the
     * confirmed once the user said the work has no other universes. The universes the model gives
     * are [made] before the characters in the chain.
     */
    private fun workUniverses(store: SourceStore, ids: Collection<String>?, made: WorkChances): Map<String, List<String>> {
        val answers = answersOf(store, ids).filter { it.facet == UniverseFacets.UNIVERSE }.groupBy { it.itemId }
        val complete = if (ids == null) store.corrections.allComplete() else store.corrections.completeOf(ids)
        val given = made
        return (answers.keys + given.keys).associateWith { id ->
            val own = answers[id].orEmpty()
            val confirmed = own.filter { it.added }.map { it.key }
            if (UniverseFacets.UNIVERSE in complete[id].orEmpty()) confirmed
            else (confirmed + ModelValues.given(given[id]?.get(UniverseFacets.UNIVERSE).orEmpty()) - own.filter { !it.added }.map { it.key }.toSet()).distinct()
        }
    }

    /** The values of a facet the user linked every item — the items [ids], when given — to: item to values. */
    private fun linked(store: SourceStore, facet: String, ids: Collection<String>?): Map<String, List<String>> =
        answersOf(store, ids).filter { it.facet == facet && it.added }.groupBy({ it.itemId }, { it.key })

    /** The user's answers on the facets of every item, or of the items [ids]. */
    private fun answersOf(store: SourceStore, ids: Collection<String>?): List<FacetCorrection> =
        if (ids == null) store.corrections.allFacets() else store.corrections.facetsOf(ids).values.flatten()

    /**
     * The values to suggest for an item, every facet the source suggests values of, with the user's
     * corrections on top: a value the user has answered on is left out. Of its [chances] made now
     * ([chances]) — passed by a caller that made them already.
     */
    fun ofItem(store: SourceStore, itemId: String, chances: Map<String, Map<String, Chance>>? = null): List<ItemFacetSuggestions> {
        val facets = facetsOf(store)
        if (facets.isEmpty() || store.items.find(itemId) == null) return emptyList()
        val corrections = store.corrections.facetsOf(itemId)
        val site = store.items.facets(itemId)
        val made = chances ?: chances(store, listOf(itemId))[itemId].orEmpty()
        return facets.filter { it.suggest && !it.infer }.map { facet ->
            val answered = corrections.filter { it.facet == facet.key }.map { it.key }.toSet()
            val current = site[facet.key].orEmpty().toSet()
            // Of a facet of one value the work has — the site's or the user's — none other is suggested
            val held = facet.single && (current.isNotEmpty() || corrections.any { it.facet == facet.key && it.added })
            val suggested = if (held) emptyList() else made[facet.key].orEmpty().values
                .filter { !it.had && it.key !in answered && it.key !in current && it.chance > ModelValues.LIKELY }.sortedByDescending { it.chance }
            ItemFacetSuggestions(facet, suggested, store.names(facet.key, suggested.map { it.key }))
        }
    }

    /**
     * What the passages of the item that name the [value] of the [facet] are found by, as its mentions
     * are counted: a character by its names, a universe by its own and its characters' — looked for in
     * the index ([Quoting.InIndex]); a pairing by its two named in one paragraph, of the index too
     * ([Quoting.Together]). Null for a facet whose values are not counted.
     */
    fun quoting(store: SourceStore, itemId: String, facet: String, value: String): Quoting? {
        val facets = facetsOf(store)
        val k = facets.indexOfFirst { it.key == facet }.takeIf { it >= 0 } ?: return null
        val ids = listOf(itemId)
        val vocabulary = universeVocabulary(store, facets[k], ids, chain(store, ids, k)) ?: return null
        val of = vocabulary.names ?: return null
        val names = of(itemId) ?: return Quoting.None
        if (vocabulary.paired) {
            val (a, b) = UniverseFacet.members(value) ?: return Quoting.None
            return Quoting.Together(names, a, b)
        }
        return Quoting.InIndex(names, names.keys.filter { value in vocabulary.valuesOf(it) })
    }

    /** What the passages naming a value are found by ([quoting]). */
    sealed interface Quoting {
        /** The names looked for in the index, and those of them that name the value. */
        class InIndex(val names: Map<String, List<String>>, val of: List<String>) : Quoting

        /** The names of the characters looked for in the index, and the two of a pairing named in one paragraph. */
        class Together(val names: Map<String, List<String>>, val a: String, val b: String) : Quoting

        /** The work's words are not counted: it names nothing. */
        data object None : Quoting
    }

    /**
     * Every value of the [facet] the item may be given and neither has nor was answered on, with its
     * chance and how often the item's texts name it: the likeliest first, then the most named. A
     * facet of characters the model has no chances of yet — none answered on to learn from — offers
     * every character of the work's universes all the same, without a chance, the most named first.
     * Null for an item or a facet the model does not work on.
     */
    fun candidates(store: SourceStore, itemId: String, facet: String): ItemFacetCandidates? {
        val facets = facetsOf(store)
        val k = facets.indexOfFirst { it.key == facet }.takeIf { it >= 0 } ?: return null
        val def = facets[k]
        if (store.items.find(itemId) == null) return null
        // NOTHING WAITED FOR, NOTHING KEPT: the chances of the facets before this one — its context —
        // and of this one made now in memory by the models kept (user, 2026-10-15)
        val ids = listOf(itemId)
        val made = chain(store, ids, k)
        val corrections = store.corrections.facetsOf(itemId)
        val site = store.items.facets(itemId)
        // Every value made, not those shown alone — of a work's universes, characters and pairings, those it may have
        val ofModel = scoredOne(store, def, itemId, made)
        val vocabulary = ofModel?.vocabulary ?: vocabularyOf(store, def, ids, made, light = true)
        val may = vocabulary?.allowed?.invoke(itemId)
        val every = ofModel?.let { everyChance(def, itemId, it, made) }
        val chances = every?.filter { may == null || it.key in may || it.had } ?: made[itemId].orEmpty()[facet].orEmpty().values
        val answered = corrections.filter { it.facet == facet }.map { it.key }.toSet()
        // The work's values as its card shows them, of the same chances: a value is never both given and offered
        val current = ModelValues.of(store.schema, site, made[itemId])[facet].orEmpty().toSet()
        // A character of the work's other facet of characters is no candidate here; nor a tag under
        // one the user said the work has not — it has none of them ([TagHierarchy])
        val hierarchy = store.hierarchies()[facet]
        // Nor a tag the user ignores: never offered
        val out = leftOut(store, facet, ids, made)(itemId) +
            corrections.filter { it.facet == facet && !it.added }.flatMap { hierarchy?.descendants(it.key).orEmpty() } +
            store.ignoredTags()[facet].orEmpty()
        // All named by the user's word: the work has its own values and is offered no other, as on its card
        val complete = facet in store.corrections.completeOf(itemId)
        // A value the model gives the work is the work's already
        val scored = if (complete) emptyList() else chances.filter { !it.had && it.key !in answered && it.key !in current && it.key !in out && !(def.infer && it.chance > ModelValues.LIKELY) }
            .map { FacetCandidate(it.key, it.chance, it.mentions) }
        val unscored = if (facet !in UniverseFacet.CHARACTER_FACETS || chances.any { !it.had } || complete) emptyList() else {
            // As the fitting counted them; of a facet never fitted, counted now — of the work's universes' characters alone
            val named = vocabularyOf(store, def, ids, made, light = true)?.mentions(store, ids, stores.typeOf(store.id).id, plugins.search)?.invoke(itemId)
            may.orEmpty()
                .filter { it !in answered && it !in current && it !in out && chances.none { c -> c.key == it } }
                .map { FacetCandidate(it, null, named?.let { n -> n[it] ?: 0 }) }
        }
        val candidates = (scored + unscored)
            .sortedWith(compareByDescending<FacetCandidate> { it.chance ?: -1.0 }.thenByDescending { it.mentions ?: 0 })
            // A tag above another before it, whatever its chance
            .let { list -> hierarchy?.parentsFirst(list) { it.key } ?: list }
        return ItemFacetCandidates(def, candidates, store.names(facet, candidates.map { it.key }))
    }

    /** The [facet]'s chances of the item by the model kept ([scored]); null when the facet was never fitted. */
    private fun scoredOne(store: SourceStore, facet: FacetDef, itemId: String, made: MadeChances): Scored? {
        val suggester = plugins.suggester(store.id, facet.key) ?: return null
        val encoder = plugins.textEncoder() ?: return null
        return scored(store, facet, suggester, encoder, listOf(itemId), made)
    }

    /**
     * The chance of EVERY value of the [facet] for the item, of its chances [scored] — of a facet whose
     * values a work may have are limited ([Vocabulary.allowed]), of those it may have — those shown
     * put among [made]; null when the item is not among them.
     */
    private fun everyChance(facet: FacetDef, itemId: String, scored: Scored, made: MadeChances): List<Chance>? {
        val data = scored.data
        val scores = scored.scores
        val i = data.ids.indexOf(itemId).takeIf { it >= 0 } ?: return null
        keep(made, facet, data, scores, scored.vocabulary)
        val chances = scores.of(intArrayOf(i)).single()
        val has = data.held[i].toHashSet()
        val named = data.task.mentions[i]
        return chances.indices.map { v -> Chance(itemId, facet.key, data.values[v], v in has, chances[v].toDouble(), named?.let { it[v] ?: 0 }) }
    }


    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /** The quiet after the last change before a source is refreshed: a page sends itself several times as it grows. */
        private const val DELAY_SECONDS = 5L

        /** The environment variable naming the folder every task of a whole facet is kept in ([SuggestionTaskFile]); unset, none is. */
        private const val TASKS_FOLDER = "RECOMMEND4ME_TASKS"

        /** Items scored at once: memory, not meaning. */
        private const val CHUNK = 2_000
    }
}

/**
 * Everything of one facet of a source a suggester is handed, read in one pass per table: every
 * item with its texts in every view and its values, every value with the vector of its name, the
 * values of the item's other facets as its context.
 */
internal class FacetData(
    val ids: List<String>,
    val values: List<String>,
    val task: SuggestionTask,
    /** The values each item has of the facet itself — the site's, the user's answers over them: not the examples the model learns from besides. */
    val held: List<IntArray>,
    /** Whether the user said each item's values of the facet are all named: it is given no other. */
    val complete: BooleanArray,
    /** The fingerprint of the whole facet: of every item's; [NO_BASIS] of the task of some works read alone. */
    val basis: Long,
) {
    companion object {
        /**
         * The rule of what is made of an item's chances and read of the others: 2 — the likeliest
         * others offered besides the likely ones; 3 — the rejected ones too; 4 — the site's examples
         * too; 5 — of a facet of limited values, not every value the work may have: the likely and
         * the offered alone; 6 — the chain: a facet reads the values the model gives of the facets
         * before it alone, made in the same pass.
         */
        private const val KEPT = 6

        /**
         * How much a drafted value the user has not confirmed weighs in what the model learns, against
         * 1 of a value the site or the user gives ([SuggestionTask.draftWeight]): a draft is the
         * dictionary matched to the site's tags — likely, but no one's word.
         */
        const val DRAFT_WEIGHT = 0.5

        /** The basis of the task of one item: none — it is never the basis the weights were fitted on. */
        const val NO_BASIS = 0L

        /** Up to this many values their names' vectors are read by their keys; more, the facet's whole at once. */
        private const val FEW_NAMES = 3000

        /**
         * What an item's chances are made of: every value of it, the user's answers — the facets said
         * to be all named ([complete]) among them — its texts' hashes, its chapters.
         */
        fun fingerprint(
            site: Map<String, List<String>>, corrections: List<FacetCorrection>, textHashes: Collection<String>, windows: Int, complete: Collection<String>,
            drafts: Collection<LayerDraft>,
        ): Long =
            Vectors.keyOf(
                site.entries.sortedBy { it.key }.joinToString("\u0001") { (f, keys) -> "$f=" + keys.sorted().joinToString(",") } + "\u0002" +
                    corrections.sortedWith(compareBy({ it.facet }, { it.key })).joinToString("\u0001") { "${it.facet}:${it.key}=${it.added}" } + "\u0002" +
                    textHashes.sorted().joinToString("\u0001") + "\u0002" + windows +
                    // Only when said, only when made: the fingerprints of the items without stay as they were
                    (if (complete.isEmpty()) "" else "\u0002complete=" + complete.sorted().joinToString(",")) +
                    (if (drafts.isEmpty()) "" else "\u0002drafts=" + drafts.map { "${it.facet}:${it.key}" }.sorted().joinToString(",")),
            )

        /**
         * [known]: values of the facet the application knows besides the items' — the universes and
         * characters of the dictionary — with the text their names are encoded from. [imageEncoder]
         * and [sets] (kind to the directions in use): the pictures' and the sets' vectors read.
         * [examples]: the values the site gives an item as examples of the facet, the item's own as
         * far as the model learns ([FacetDef.examplesFrom]); a value it lacks weighs [lackWeight].
         *
         * [only]: THE TASK OF THOSE WORKS ALONE — their rows, judged by the model kept, which reads a
         * task by the keys of its values, its context and its blocks: their inputs, values, context
         * are read of them alone, not of every work; the values chosen from are those the model kept
         * chooses from ([valueKeys]) and their own. No [basis]: what the weights were fitted on is told
         * by the background refresh, which reads every work.
         *
         * [modelled]: the values the model gives of the facets before this one in the chain, made in
         * the same pass, and the drafts of the layer; of the others the site's and the user's alone.
         *
         * [kept]: THE TASK OF THE SCORING KEPT ([FittedSuggester.scoring]) — its values those of the
         * scoring, in its order, without names, groups or texts of them: the scoring knows each; the
         * works' own features alone read, of every work or of the works [only]. No [basis].
         */
        fun load(
            store: SourceStore, facet: FacetDef, encoder: TextEncoder, known: Map<String, String> = emptyMap(),
            imageEncoder: String? = null, sets: Map<SetKind, Long> = emptyMap(), only: Collection<String>? = null,
            modelled: WorkChances = emptyMap(), valueKeys: List<String>? = null,
            mentions: ((String) -> Map<String, Int>?)? = null,
            allowed: ((String) -> Set<String>)? = null,
            groupings: ((List<String>) -> List<ValueGrouping>)? = null,
            examples: ((String) -> List<String>)? = null,
            lackWeight: Double = 1.0,
            hintNames: Map<String, List<String>> = emptyMap(),
            hintAmong: (String) -> Collection<String> = { emptyList() },
            kept: List<String>? = null,
        ): FacetData {
            val schema = store.schema
            // The rows of the task: every work, or the works [only]; their values alone read
            val every = only?.distinct() ?: store.items.keys().map { it.id }
            val ids = every
            val site = HashMap<String, HashMap<String, MutableList<String>>>()
            if (only == null) store.items.forEachFacetValue { id, f, key -> site.getOrPut(id) { HashMap() }.getOrPut(f) { ArrayList() } += key }
            else store.items.facetsOf(every).forEach { (id, facets) -> site[id] = HashMap(facets.mapValues { it.value.toMutableList() }) }
            val corrections = if (only == null) store.corrections.allFacets().groupBy { it.itemId } else store.corrections.facetsOf(every)
            // Of a facet of tags, the user's answers with what they say of the tags above and under them
            val hierarchy = store.hierarchies()[facet.key]
            val answers = if (hierarchy == null) corrections else corrections.mapValues { hierarchy.expand(facet.key, it.value) }
            val within = hierarchy?.let { mapOf(facet.key to it) }.orEmpty()
            val complete = if (only == null) store.corrections.allComplete() else store.corrections.completeOf(every)
            // The examples are the facet's values as far as the model learns, the user's answers over
            // them — the facet the user corrects; the site's facet they come from is among the item's inputs
            // And the drafts of the layer ([LayerDrafts][io.github.vlsergey.recommend4me.draft.LayerDrafts]): values the item
            // has unless the user said otherwise, learnt from with less weight ([DRAFT_WEIGHT]) — they are no one's word
            val drafts = if (only == null) store.drafts.all() else store.drafts.ofItems(every)
            val drafted = drafts.mapValues { (_, list) -> list.filter { it.facet == facet.key }.map { it.key } }.filterValues { it.isNotEmpty() }
            val learnt = if (examples == null && drafted.isEmpty()) site else HashMap(site).also { all ->
                every.forEach { id ->
                    val own = examples?.invoke(id).orEmpty() + drafted[id].orEmpty()
                    if (own.isNotEmpty()) all[id] = HashMap(site[id].orEmpty()).apply { this[facet.key] = (this[facet.key].orEmpty() + own).distinct().toMutableList() }
                }
            }
            val corrected = every.associateWith { id -> TagHierarchy.corrected(learnt[id].orEmpty(), corrections[id].orEmpty(), within) }

            // Every value of the facet the site or the user gave any work read, the values the model kept
            // chooses from, and every value known besides
            val everyValue = (corrected.values.flatMap { it[facet.key].orEmpty() } +
                answers.values.flatten().filter { it.facet == facet.key }.map { it.key } + known.keys + valueKeys.orEmpty()).distinct().sorted()
            // Of works of a facet whose values a work may have are limited ([allowed]: a work's
            // characters are of its universes), those they may have and their own alone: no other is
            // chosen from — of a work of two universes some thousand characters, not every one
            val values = if (kept != null) kept else if (only == null || allowed == null) everyValue else {
                val own = HashSet<String>()
                every.forEach { id ->
                    own += allowed(id)
                    own += corrected[id]?.get(facet.key).orEmpty()
                    answers[id].orEmpty().filter { it.facet == facet.key }.forEach { own += it.key }
                    own += drafted[id].orEmpty()
                    own += examples?.invoke(id).orEmpty()
                }
                everyValue.filter { it in own }
            }
            val index = values.withIndex().associate { (i, k) -> k to i }

            // The other facets' values as the context — of the facets the source has now, the site's own among them.
            // THE CHAIN: of the facets before this one, the values the model gives the item besides the
            // site's and the user's, made in the same pass ([modelled] holds those alone, and the drafts);
            // the tags with those above them
            val hierarchies = store.hierarchies()
            val facets = schema.facets.map { it.key }.toSet()
            val contextOf = ids.map { id ->
                TagHierarchy.corrected(ModelValues.of(schema, learnt[id].orEmpty(), modelled[id]), corrections[id].orEmpty(), hierarchies)
                    .filterKeys { it != facet.key && it in facets }
                    .flatMap { (f, keys) -> keys.map { "$f\u0001$it" } }.distinct()
            }
            // A value of the context only one item of the fitting has teaches nothing of the others: no
            // column of it — of a catalogue of games, the developers of one game each, the operating
            // systems and languages written by hand once. An item judged alone ([only]) is read by the
            // columns of the layout fitted last: every value of its own kept
            val shared = if (only != null) null else HashMap<String, Int>().also { n -> contextOf.forEach { keys -> keys.forEach { n.merge(it, 1, Int::plus) } } }
            val contextIndex = HashMap<String, Int>()
            val context = contextOf.map { keys ->
                keys.filter { shared == null || shared.getValue(it) > 1 }.map { contextIndex.getOrPut(it) { contextIndex.size } }.toIntArray()
            }

            val d = encoder.dim
            // A facet the site gives values of has them as its labels: its own line on the site is
            // those values written out, no input of it — as the facet is none of its own context
            // — whether the site gives values of it told of every work, as the model was fitted, not of the works read
            val siteGives = if (only == null) site.values.any { facet.key in it } else store.items.valueUses(facet.key).isNotEmpty()
            val ownLine = facet.original?.takeIf { siteGives }
            val inputs = inputs(store, ids, encoder, imageEncoder, sets, only, leaveOut = setOfNotNull(ownLine))

            // Of the scoring kept, no names: it knows every value's coordinates
            val names = Matrix(values.size, if (kept != null) 0 else d)
            if (kept == null) nameVectors(store, facet.key, values, corrections.values.flatten(), known, encoder, asKept = only != null).forEach { (key, v) ->
                index[key]?.let { names.held.put(v, 0, names.row(it), d) }
            }

            fun indices(list: Collection<String>) = list.mapNotNull { index[it] }.distinct().toIntArray()
            val grouped = groupings?.invoke(values).orEmpty()
            // An item whose values of the facet are all named: every other value rejected by the user's word
            val allNamed = BooleanArray(ids.size) { facet.key in complete[ids[it]].orEmpty() }
            val assigned = ids.map { indices(corrected[it]?.get(facet.key).orEmpty()) }
            // What is learnt from, not what is judged: no names when the item [only] is judged by the weights fitted last
            val (hintValues, hintTexts) = if (only != null || kept != null) emptyList<String>() to Matrix(0, 0) else hints(store, facet.key, hintNames, encoder)
            // The names of values of the task: every one, but of a dictionary's value the task lacks
            val named = hintValues.indices.filter { index[hintValues[it]] != null }
            val task = SuggestionTask(
                itemIds = ids,
                inputs = inputs,
                values = names,
                valueKeys = values,
                assigned = assigned,
                confirmed = ids.map { id -> indices(answers[id].orEmpty().filter { it.facet == facet.key && it.added }.map { it.key }) },
                rejected = ids.mapIndexed { i, id ->
                    if (allNamed[i]) assigned[i].toHashSet().let { own -> values.indices.filter { it !in own }.toIntArray() }
                    else indices(answers[id].orEmpty().filter { it.facet == facet.key && !it.added }.map { it.key })
                },
                context = context,
                contextKeys = contextIndex.entries.sortedBy { it.value }.map { it.key },
                mentions = ids.map { id -> mentions?.invoke(id)?.mapNotNull { (key, n) -> index[key]?.let { it to n } }?.toMap() },
                allowed = ids.map { id -> allowed?.invoke(id)?.let(::indices) },
                groupings = grouped,
                lackWeight = lackWeight,
                examples = ids.map { id -> examples?.invoke(id)?.let(::indices) ?: IntArray(0) },
                drafts = ids.map { id -> indices(drafted[id].orEmpty()) },
                draftWeight = DRAFT_WEIGHT,
                hints = run {
                    // Every collection of values the names are judged among made indices once, one array
                    // shared by all its names: of a universe's characters thousands of names, each of
                    // thousands of rivals
                    val made = IdentityHashMap<Collection<String>, IntArray>()
                    named.map { k ->
                        val key = hintValues[k]
                        val v = index.getValue(key)
                        val of = hintAmong(key)
                        val among = made.getOrPut(of) { indices(of).also { it.sort() } }
                        NameHint(v, if (among.binarySearch(v) >= 0) among else (among + v).also { it.sort() })
                    }
                },
                hintTexts = if (named.size == hintValues.size) hintTexts else hintTexts.gather(named.toIntArray()),
            )
            // The item's own values: neither the site's examples nor the drafts
            val held = if (examples == null && drafted.isEmpty()) task.assigned else ids.map { id ->
                indices(TagHierarchy.corrected(site[id].orEmpty(), corrections[id].orEmpty(), within)[facet.key].orEmpty())
            }
            // The task of some works, or of the scoring kept, tells nothing of what the weights were fitted on
            if (only != null || kept != null) return FacetData(ids, values, task, held, allNamed, NO_BASIS)
            val hashes = HashMap<String, MutableList<String>>()
            store.textVectors.hashes(encoder.id).forEach { (k, hash) -> hashes.getOrPut(k.first) { ArrayList() } += hash }
            val windowCounts = store.parts.windowCounts(encoder.id)
            val fingerprints = LongArray(ids.size) { i ->
                val id = ids[i]
                fingerprint(site[id].orEmpty(), corrections[id].orEmpty(), hashes[id].orEmpty(), windowCounts[id] ?: 0, complete[id].orEmpty(), drafts[id].orEmpty())
            }
            // The values chosen from and their groups are part of what is learnt: a universe added or
            // removed, a sex learnt of a character, fits again
            // What is kept of the chances is part of it too: a new rule of what to keep makes them again
            // And the names the values go by: an alias added to the dictionary is a new text to learn from
            val basis = Vectors.keyOf(
                ids.indices.sortedBy { ids[it] }.joinToString(",") { "${ids[it]}:${fingerprints[it]}" } + "\u0002" + values.joinToString("\u0001") +
                    "\u0002" + grouped.joinToString("\u0001") { g -> g.name + "=" + g.of.joinToString(",") { groups -> groups.joinToString("+") { g.groups[it] } } } + "\u0002" + KEPT +
                    "\u0002" + Vectors.keyOf(hintNames.entries.sortedBy { it.key }.joinToString("\u0001") { (v, names) -> v + "=" + names.joinToString("|") }) +
                    // The tags under others: a tag put under another is learnt again
                    "\u0002" + (hierarchy?.signature() ?: "") +
                    // The other facets' values as the site and the user give them: one of them changed is learnt
                    // from again. NOT WHAT THE MODEL GAVE of the other facets: each refit of one changes what the
                    // next reads, the circle never settles, and every start would fit every facet again for
                    // nothing new. The model's values are read as they are when the chances are made
                    "\u0002" + Vectors.keyOf(
                        ids.indices.sortedBy { ids[it] }.joinToString(",") { i ->
                            val id = ids[i]
                            id + ":" + TagHierarchy.corrected(ModelValues.of(schema, learnt[id].orEmpty(), null), corrections[id].orEmpty(), hierarchies)
                                .filterKeys { it != facet.key && it in facets }.entries.sortedBy { it.key }
                                .joinToString(";") { (f, keys) -> f + "=" + keys.sorted().joinToString("+") }
                        },
                    ),
            )
            return FacetData(ids, values, task, held, allNamed, basis)
        }

        /**
         * Every input of the [ids], as the model of the grades reads them: a block of every text
         * that has vectors (the title, the description, the notes, the site's lines), of every
         * picture block, of every set (the reviews, the windows of the chapters, the screenshots).
         * Of the works [only] alone when given, read by their keys; of every work streamed.
         */
        private fun inputs(
            store: SourceStore, ids: List<String>, encoder: TextEncoder, imageEncoder: String?, sets: Map<SetKind, Long>, only: Collection<String>?,
            /** The texts not read, by their keys. */
            leaveOut: Set<String>,
        ): List<InputBlock> {
            val row = ids.withIndex().associate { (i, id) -> id to i }
            val blocks = LinkedHashMap<String, Pair<Matrix, BooleanArray>>()
            fun put(id: String, block: String, v: FloatArray) {
                val i = row[id] ?: return
                val (m, present) = blocks.getOrPut(block) { Matrix(ids.size, v.size) to BooleanArray(ids.size) }
                // A vector of another length is of another encoder: not of this block
                if (v.size != m.cols) return
                m.held.put(v, 0, m.row(i), v.size)
                present[i] = true
            }
            val texts = TextVectors.blocks(store.id, store.schema).mapValues { it.value.first } - leaveOut
            if (only == null) {
                store.textVectors.forEach(encoder.id, texts.keys) { id, key, v -> put(id, texts.getValue(key), v) }
                imageEncoder?.let { e -> store.pictures.forEachItemVector(e) { id, block, v -> put(id, block, v) } }
                store.setVectors.forEach(sets) { id, block, v -> put(id, block, v) }
            } else {
                store.textVectors.ofMany(ids, encoder.id).forEach { (id, own) -> own.forEach { (key, v) -> texts[key]?.let { put(id, it, v) } } }
                imageEncoder?.let { e -> store.pictures.itemVectorsOf(ids, e).forEach { (id, own) -> own.forEach { (block, v) -> put(id, block, v) } } }
                store.setVectors.ofMany(ids, sets).forEach { (id, own) -> own.forEach { (block, v) -> put(id, block, v) } }
            }
            return blocks.map { (name, b) -> InputBlock(name, b.first, b.second) }
        }

        /**
         * The vectors of the values' names as queries — of [known]'s texts for those it has — encoded
         * now where missing or the name changed. [asKept]: of works read by the model kept, the vector
         * kept of a value is its vector whatever its name now — the one the model was fitted with; a
         * renamed value is encoded again at the next fitting, not at every reading.
         */
        private fun nameVectors(
            store: SourceStore, facet: String, values: List<String>, corrections: List<FacetCorrection>, known: Map<String, String>, encoder: TextEncoder,
            asKept: Boolean = false,
        ): Map<String, FloatArray> {
            // The names [known] gives are the dictionary's: the source asked of the others alone
            val names = store.names(facet, values.filter { it !in known }) + corrections.filter { it.facet == facet && it.name != null }.associate { it.key to it.name!! } + known
            // Of a few values — one item's — those alone read; of every value, the facet's whole
            val stored = store.valueNames.of(facet, encoder.id, values.takeIf { it.size <= FEW_NAMES })
            val out = HashMap<String, FloatArray>()
            val todo = ArrayList<Pair<String, String>>()
            values.forEach { key ->
                val name = names[key] ?: key
                val have = stored[key]
                if (have != null && (asKept || have.first == Vectors.hash(name))) out[key] = have.second else todo += key to name
            }
            todo.chunked(ENCODE_BATCH).forEach { batch ->
                val vectors = encoder.encode(batch.map { it.second }, TextKind.QUERY)
                val rows = batch.indices.mapNotNull { k -> vectors[k]?.let { Triple(batch[k].first, Vectors.hash(batch[k].second), it) } }
                store.valueNames.save(facet, encoder.id, rows)
                rows.forEach { (key, _, v) -> out[key] = v }
            }
            return out
        }

        /**
         * Every name of every value as a text of a work is encoded — a passage, not a query — each
         * with the value it names; kept with the source's vectors of names under "hint:<facet>",
         * encoded again only when new. The value of every name, and the names' vectors as the rows of
         * one matrix, read into it as they are streamed: thousands of names of a thousand numbers
         * each are not held twice. A name the encoder gave no vector is left out.
         */
        private fun hints(store: SourceStore, facet: String, names: Map<String, List<String>>, encoder: TextEncoder): Pair<List<String>, Matrix> {
            if (names.isEmpty()) return emptyList<String>() to Matrix(0, 0)
            val kept = "hint:$facet"
            val wanted = names.flatMap { (value, list) -> list.distinct().map { value to it } }
            val at = wanted.withIndex().associate { (k, w) -> "${w.first}\u0001${w.second}" to k }
            val hashes = wanted.map { Vectors.hash(it.second) }
            val texts = Matrix(wanted.size, encoder.dim)
            val have = BooleanArray(wanted.size)
            fun put(k: Int, v: FloatArray) {
                if (v.size != texts.cols) return
                texts.held.put(v, 0, texts.row(k), texts.cols)
                have[k] = true
            }
            store.valueNames.forEach(kept, encoder.id) { key, hash, v -> at[key]?.takeIf { hashes[it] == hash }?.let { put(it, v) } }
            wanted.indices.filter { !have[it] }.chunked(ENCODE_BATCH).forEach { batch ->
                val vectors = encoder.encode(batch.map { wanted[it].second }, TextKind.PASSAGE)
                val rows = batch.indices.mapNotNull { j -> vectors[j]?.let { Triple("${wanted[batch[j]].first}\u0001${wanted[batch[j]].second}", hashes[batch[j]], it) } }
                store.valueNames.save(kept, encoder.id, rows)
                batch.indices.forEach { j -> vectors[j]?.let { put(batch[j], it) } }
            }
            val rows = wanted.indices.filter { have[it] }
            return rows.map { wanted[it].first } to if (rows.size == wanted.size) texts else texts.gather(rows.toIntArray())
        }

        /** Names encoded in one call: memory, not meaning. */
        private const val ENCODE_BATCH = 64
    }
}
