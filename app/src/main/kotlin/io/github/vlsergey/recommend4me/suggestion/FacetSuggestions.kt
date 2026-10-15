package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.Corrected
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
import io.github.vlsergey.recommend4me.universe.Mentions
import io.github.vlsergey.recommend4me.universe.Naming
import io.github.vlsergey.recommend4me.universe.Quote
import io.github.vlsergey.recommend4me.universe.everyName
import io.github.vlsergey.recommend4me.universe.workPassages
import io.github.vlsergey.recommend4me.universe.workTexts
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(FacetSuggestions::class.java)

/** The model gave items of a source other values of a facet it works out: what reads them reads them again. */
data class FacetChancesChanged(val source: String, val ids: List<String>)

/** The values of a facet an item lacks and more likely has than not, as the interface shows them. */
class ItemFacetSuggestions(val facet: FacetDef, val suggested: List<Chance>, val names: Map<String, String>)

/** A value a work may be given: the model's chance of it when it has one, how often the work's texts name it when counted. */
class FacetCandidate(val key: String, val chance: Double?, val mentions: Int?)

/** The values the user may yet give a work of one facet, the likeliest first, with their names. */
class ItemFacetCandidates(val facet: FacetDef, val candidates: List<FacetCandidate>, val names: Map<String, String>)

/**
 * WHAT THE MODEL SAYS OF THE VALUES OF FACETS, by the suggester plugin, for every facet a source
 * marks [suggest][FacetDef.suggest] or [infer][FacetDef.infer]: the chance of every value a work
 * has, and of every value it lacks that it more likely has than not. Kept in the model database of
 * the content type with what each item's chances were made of — every value of the item, the
 * user's answers, its texts, its chapters — as a fingerprint.
 *
 * The suggester is fitted again whenever anything it learns from has changed (the fingerprint of
 * the whole facet), and every item is then worked out again. A page that asks for an item that
 * never had chances gets them at once, by the weights fitted last. An answer of the user is not
 * kept waiting for the chances it changes: they are made apart ([answered]), and a making of them
 * — of a work, of the whole source — is given up for a newer change and made again after it.
 *
 * NOTHING IS KEPT BETWEEN OPERATIONS: each one reads the source's items, values and vectors in one
 * pass, and drops them at its end. Only the work waiting is kept: the sources with changes, how
 * many changes each had, the works whose chances are being made after answers.
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

    /** The items whose chances are being made current after the user's answers ([answered]), by "source/item"; guarded by itself. */
    private val answering = HashMap<String, Answering>()

    /** The making of chances after answers: apart from the background refresh, which takes seconds a facet. */
    private val answers = Executors.newSingleThreadExecutor { r -> Thread(r, "answers").apply { isDaemon = true } }

    /**
     * The facets the model works on, in the order they are worked out: those it gives values of
     * first — their values are the context of the others, and the pairings are of the characters
     * given before them — so that one round settles them all.
     */
    fun facetsOf(store: SourceStore): List<FacetDef> = store.schema.facets.filter { it.suggest || it.infer }.sortedBy { !it.infer }

    @EventListener
    fun itemsChanged(event: ItemsChanged) = changed(event.source, "items")

    @EventListener
    fun textVectorsChanged(event: TextVectorsChanged) = changed(event.source, "texts")

    @EventListener
    fun partsSaved(event: PartsSaved) = changed(event.source, "parts")

    /**
     * Something the source's chances are made of changed — the [reason], a key the interface words,
     * told in the queue of the works till the refresh begins: the source is refreshed after the
     * quiet that follows. A refresh under way is given up ([newer]): it works on what it read before
     * the change, and the one after the quiet works on all of it.
     */
    private fun changed(source: String, reason: String, newer: Boolean = true) {
        val store = stores.source(source) ?: return
        if (facetsOf(store).isEmpty()) return
        if (newer) generationOf(source).incrementAndGet()
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
     * Fits the suggester of every facet of the source again when what it learns from changed, and
     * works out every item whose fingerprint changed — every item after a fitting. For a caller that
     * waits for it: carried through whatever changes meanwhile; those are refreshed after their quiet.
     */
    fun refresh(store: SourceStore) = refresh(store, givenUp = false, mapOf("asked" to 1))

    /**
     * [refresh]; [givenUp] — with a [CancellationException] once the source changes again
     * ([changed]): the background's; [reasons] — what it is made for, told in the queue of the works
     * beside those every facet was planned for ([changed]); null — those alone.
     */
    private fun refresh(store: SourceStore, givenUp: Boolean, reasons: Map<String, Int>?) {
        val encoder = plugins.textEncoder() ?: return
        val generation = generationOf(store.id).get()
        val stop = if (givenUp) ({ generationOf(store.id).get() != generation }) else ({ false })
        facetsOf(store).forEach { facet ->
            val suggester = plugins.suggester(store.id, facet.key) ?: return@forEach
            val work = refreshing(store.id, facet)
            val planned = workload.take(work)
            workload.run(work, reasons.orEmpty().toMutableMap().apply { planned.forEach { (k, n) -> merge(k, n, Int::plus) } }) { lockOf(store, facet).withLock { refreshFacet(store, facet, suggester, encoder, null, stop) } }
        }
    }

    /**
     * The facet's suggester fitted again FROM SCRATCH — every setting tried anew, not a short walk on
     * from the state fitted last — and every item worked out again: the user asked for it on the page
     * of the models. In the background; false when the source has no such facet.
     */
    fun searchAnew(store: SourceStore, facetKey: String): Boolean {
        val facet = facetsOf(store).firstOrNull { it.key == facetKey } ?: return false
        if (plugins.suggester(store.id, facet.key) == null) return false
        val encoder = plugins.textEncoder() ?: return false
        val id = "${store.id}/${facet.key}"
        searching += id
        worker.execute {
            try {
                // The suggester named when it runs: one chosen meanwhile is the one fitted
                val suggester = plugins.suggester(store.id, facet.key) ?: return@execute
                workload.run(Work("suggesterAnew", source = store.id, facet = facet.key), mapOf("asked" to 1)) { lockOf(store, facet).withLock { refreshFacet(store, facet, suggester, encoder, null, anew = true) } }
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
        val facet = facetsOf(store).firstOrNull { it.key == facetKey } ?: return null
        val encoder = plugins.textEncoder() ?: return null
        val vocabulary = universeVocabulary(store, facet, null) ?: examplesVocabulary(store, facet)
        return load(store, facet, encoder, vocabulary, null).task
    }

    /** The facets being fitted anew now ([searchAnew]), by "source/facet": the work under way, nothing of the data. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether the facet's suggester is being fitted anew ([searchAnew]). */
    fun searching(store: SourceStore, facetKey: String): Boolean = "${store.id}/$facetKey" in searching

    /** One lock a facet: a page waits only for the facet it asks of, not for a long fitting of another. */
    private fun lockOf(store: SourceStore, facet: FacetDef) = locks.computeIfAbsent("${store.id}/${facet.key}") { ReentrantLock() }

    /**
     * The item's chances of the [facets] made now, by the weights fitted last — a facet never fitted
     * is fitted in the background, the page does not wait for it. Not under the lock of
     * the facet: a refit of the whole facet takes seconds, and the user waits for these. A refit
     * under way may write the item's chances made of what it read before; their fingerprint then
     * tells they are stale, and they are made again.
     */
    private fun refreshNow(store: SourceStore, itemId: String, facets: List<FacetDef>, stop: () -> Boolean = { false }) {
        val encoder = plugins.textEncoder() ?: return
        facets.forEach { facet -> plugins.suggester(store.id, facet.key)?.let { refreshFacet(store, facet, it, encoder, itemId, stop) } }
    }

    /** What the model of the facet reads — of the item [only] alone, when given. */
    private fun load(store: SourceStore, facet: FacetDef, encoder: TextEncoder, vocabulary: Vocabulary?, only: String?) = FacetData.load(
        store, facet, encoder, vocabulary?.values.orEmpty(),
        imageEncoder = plugins.imageEncoder()?.id, sets = stores.typeOf(store.id).embeddings.ids(), only = only,
        // Counted in the texts of the item [only] alone: no other item's chances are made
        mentions = vocabulary?.mentions(store, only),
        allowed = vocabulary?.allowed, groupings = vocabulary?.groupings, examples = vocabulary?.examples, lackWeight = vocabulary?.lackWeight ?: 1.0,
        hintNames = vocabulary?.hintNames.orEmpty(), hintAmong = vocabulary?.hintAmong ?: { emptyList() },
    )

    /**
     * The chance of EVERY value of the facet for the item — of a facet whose values a work may have
     * are limited ([Vocabulary.allowed]), of those it may have — made now by the weights fitted last
     * and not kept: only the likeliest of an item's chances are kept, and the user who asks for more
     * gets the rest from here. Null when the facet was never fitted.
     */
    private fun everyChance(store: SourceStore, facet: FacetDef, itemId: String, vocabulary: Vocabulary?): List<Chance>? {
        val suggester = plugins.suggester(store.id, facet.key) ?: return null
        val encoder = plugins.textEncoder() ?: return null
        val data = load(store, facet, encoder, vocabulary, itemId)
        val i = data.ids.indexOf(itemId).takeIf { it >= 0 } ?: return null
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id } ?: return null
        val fitted = suggester.unpack(stored.content, data.task) ?: return null
        val chances = fitted.on(data.task).of(intArrayOf(i)).single()
        val has = data.held[i].toHashSet()
        val named = data.task.mentions[i]
        return chances.indices.map { v -> Chance(itemId, facet.key, data.values[v], v in has, chances[v].toDouble(), named?.let { it[v] ?: 0 }) }
    }

    /**
     * The facet worked out: of every item whose fingerprint changed — of the item [only], by the
     * weights fitted last, when given. [stop] is asked between the stages, and by the fitting
     * between its steps: newer data came, and the work is given up with a [CancellationException]
     * before anything made of the old is kept.
     */
    private fun refreshFacet(
        store: SourceStore, facet: FacetDef, suggester: FacetSuggester, encoder: TextEncoder, only: String?, stop: () -> Boolean = { false },
        /** Fitted again from scratch, every setting tried anew, whatever it was fitted on — the user asked for it ([searchAnew]). */
        anew: Boolean = false,
    ) {
        fun checkpoint() {
            if (stop()) throw CancellationException("${store.id}: ${facet.key} given up for newer changes")
        }
        val started = System.currentTimeMillis()
        val vocabulary = universeVocabulary(store, facet, only) ?: examplesVocabulary(store, facet)
        val vocabularyMillis = System.currentTimeMillis() - started
        checkpoint()
        val data = load(store, facet, encoder, vocabulary, only)
        val read = System.currentTimeMillis() - started
        checkpoint()
        // The whole task kept in a file when asked for: its fitting made again and measured apart from the application
        if (only == null) System.getenv(TASKS_FOLDER)?.let { dir -> SuggestionTaskFile.write(data.task, Path.of(dir, "${store.id}-${facet.key}.task.gz")) }
        val now = Instant.now()
        if (data.values.isEmpty()) return nothingToGive(store, facet, data, only, now)
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id }
        val kept = stored?.let { suggester.unpack(it.content, data.task) }
        val unpacked = System.currentTimeMillis() - started
        checkpoint()
        if (kept == null && only != null) {
            // Not fitted, or fitted on what no longer fits: a page never waits for a fitting of the
            // whole facet — minutes for the characters of every universe. The facet is fitted in the
            // background; an item that never had chances is kept with none until then, so a read
            // does not ask again (the characters are offered without chances meanwhile). Not a newer
            // change: a fitting under way goes on
            if (store.suggestions.fingerprint(only, facet.key) == null) nothingToGive(store, facet, data, only, now)
            return changed(store.id, "notFitted", newer = false)
        }
        // Of one item's task never: what the weights were fitted on is told by the background refresh ([FacetData.load])
        val current = !anew && only == null && kept != null && stored.basis == data.basis
        val fitted = when {
            current || only != null -> kept ?: return
            else -> {
                // From the state fitted last when there is one: the data moved a little since
                val fitStarted = System.currentTimeMillis()
                val made = suggester.fit(data.task, if (anew) null else kept, stop) ?: return nothingToGive(store, facet, data, null, now)
                checkpoint()
                val fit = SuggesterFit(made.report, System.currentTimeMillis() - fitStarted, data.ids.size, data.values.size)
                store.suggestions.saveSuggester(facet.key, suggester.id, now, data.basis, made.pack(), fit)
                log.info("{}: the suggester of {} fitted on {} items, {} values in {} ms, {} of them reading", store.id, facet.key, data.ids.size, data.values.size, System.currentTimeMillis() - started, read)
                made
            }
        }
        // Fitted on other data: the whole facet is fitted again and worked out in the background —
        // after the change that made it other, which was a newer one itself
        if (!current && only != null) changed(store.id, "stale", newer = false)
        val refitted = !current && only == null
        // The fingerprints the chances were made of, read before the scoring: of the whole facet, what the writing compares with
        val fingerprintsRead = if (only == null) store.suggestions.fingerprints(facet.key) else null
        val known = fingerprintsRead ?: mapOf(only to store.suggestions.fingerprint(only!!, facet.key))
        val rows = data.ids.indices.filter { i ->
            val id = data.ids[i]
            refitted || ((only == null || id == only) && known[id] != data.fingerprints[i])
        }
        if (rows.isEmpty()) return
        val fittedAt = System.currentTimeMillis() - started
        val scores = fitted.on(data.task)
        // What the model gave alone before: the drafts are not of its making — of this facet alone
        val before = if (facet.infer) store.suggestions.givenOf(facet.key, only) else emptyMap()
        val beforeAt = System.currentTimeMillis() - started
        val changedIds = ArrayList<String>()
        var scored = 0L
        // Of every item: the scores, what is kept of them, the writing — each summed over the chunks
        var scoring = 0L
        var keeping = 0L
        var writing = 0L
        rows.chunked(CHUNK).forEach { chunk ->
            val at = System.currentTimeMillis()
            val chances = scores.of(chunk.toIntArray())
            scored = System.currentTimeMillis() - started
            scoring += System.currentTimeMillis() - at
            val made = HashMap<String, Long>()
            val out = ArrayList<Chance>()
            val keptAt = System.currentTimeMillis()
            chunk.forEachIndexed { k, i ->
                val id = data.ids[i]
                made[id] = data.fingerprints[i]
                val own = chancesOf(id, facet.key, chances[k], data, i, vocabulary?.allowed?.invoke(id))
                out += own
                if (facet.infer && ModelValues.given(own.associateBy { it.key }).toSet() != before[id].orEmpty()) changedIds += id
            }
            val writeAt = System.currentTimeMillis()
            keeping += writeAt - keptAt
            // Of the whole facet: a work whose chances an answer made since [known] was read keeps those
            store.suggestions.replace(facet.key, made, out, now, expected = fingerprintsRead)
            writing += System.currentTimeMillis() - writeAt
        }
        if (changedIds.isNotEmpty()) events.publishEvent(FacetChancesChanged(store.id, changedIds))
        if (rows.size > 1) log.info(
            "{}: chances of {} of {} items made in {} ms — the dictionary {}, reading {}, the weights {}, fitting {}, the chances before {}, scoring {}, keeping {}, writing {}",
            store.id, facet.key, rows.size, System.currentTimeMillis() - started,
            vocabularyMillis, read - vocabularyMillis, unpacked - read, fittedAt - unpacked, beforeAt - fittedAt, scoring, keeping, writing,
        )
        // What an answer waits for, a stage at a time: the whole is made again for one work
        else if (only != null) log.info(
            "{}: chances of {} of {} made in {} ms — the dictionary {}, reading {}, the weights {}, the chances {}, kept {}",
            store.id, facet.key, only, System.currentTimeMillis() - started,
            vocabularyMillis, read - vocabularyMillis, unpacked - read, scored - unpacked, System.currentTimeMillis() - started - scored,
        )
    }

    /**
     * The facet has nothing to give: no values, or no work the user said has one to learn from (the
     * secondary characters before the first answer). Every item — the item [only], when given — is
     * kept as worked out with no chances, by its fingerprint: a read does not try again, a change of
     * the item or of what the facet learns from does.
     */
    private fun nothingToGive(store: SourceStore, facet: FacetDef, data: FacetData, only: String?, now: Instant) {
        val known = store.suggestions.fingerprints(facet.key)
        val made = data.ids.indices.filter { (only == null || data.ids[it] == only) && known[data.ids[it]] != data.fingerprints[it] }
            .associate { data.ids[it] to data.fingerprints[it] }
        made.keys.chunked(CHUNK).forEach { chunk -> store.suggestions.replace(facet.key, chunk.associateWith { made.getValue(it) }, emptyList(), now) }
    }

    /**
     * What is kept of item [i]'s chances: of every value it has, of every value the user rejected
     * (shown crossed out, in its place by its chance), every one the site gives it as an example —
     * offered to answer on whatever its chance, shown with it — and of the values it lacks those it
     * more likely has than not and the likeliest others offered besides; of a facet that says which
     * values a work may have ([allowed]: a work's characters are of its universes, its pairings of
     * its characters) of those alone. Not every value it may have: of a work of a large universe
     * thousands of characters, written at every refresh — the user choosing among them gets every one
     * made at the asking ([candidates]).
     */
    private fun chancesOf(id: String, facet: String, chances: FloatArray, data: FacetData, i: Int, allowed: Set<String>?): List<Chance> {
        val has = data.held[i].toHashSet()
        val rejected = data.task.rejected[i].toHashSet()
        val named = data.task.mentions[i]
        fun chance(v: Int, had: Boolean) = Chance(id, facet, data.values[v], had, chances[v].toDouble(), named?.let { it[v] ?: 0 })
        // All named by the user's word: the item has its own values and is offered no other
        if (data.complete[i]) return has.map { chance(it, true) }
        fun may(v: Int) = allowed == null || data.values[v] in allowed
        // The likeliest of the rest too, likely or not, to offer the work at least so many the site does not give
        val unanswered = chances.indices.filter { it !in has && it !in rejected && may(it) }
        val offered = ModelValues.offered(
            unanswered.associate { data.values[it] to chances[it].toDouble() },
            shown = unanswered.filter { chances[it] > ModelValues.LIKELY }.map { data.values[it] }.toSet(),
            answered = emptySet(),
            fromSite = data.task.examples[i].map { data.values[it] }.toSet(),
        ).toSet()
        val examples = data.task.examples[i].toHashSet()
        return chances.indices.mapNotNull { v ->
            when {
                v in has -> chance(v, true)
                // The site's own: shown to answer on, unlikely as it may be
                v in examples -> chance(v, false)
                // A value the user rejected keeps its chance: it is shown crossed out, in its place by it
                v in rejected -> chance(v, false)
                may(v) && (chances[v] > ModelValues.LIKELY || data.values[v] in offered) -> chance(v, false)
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
        /** How the item's own texts name the values; null for an item not counted. */
        val naming: ((String) -> Naming?)? = null,
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
        /** How often each item's own texts name each value, by [naming]; null when the values are not counted. */
        fun mentions(store: SourceStore, only: String?): ((String) -> Map<String, Int>?)? = naming?.let { of ->
            // Of every work: the values' names read once for them all, not a query of each work's
            val names = if (only == null) store.everyName() else store.items::facetNames
            return { id -> if (only != null && id != only) null else of(id)?.count(store.workTexts(id, names)) }
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
     */
    private fun examplesVocabulary(store: SourceStore, facet: FacetDef): Vocabulary? {
        val from = store.schema.facets.firstOrNull { it.key == facet.examplesFrom } ?: return null
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
     * The vocabulary of a facet of the universes; of the item [only], when given, what depends on the
     * items read of it alone — its universes, the characters of its other facet, its pairs — not of
     * every item: of a work of a large universe the chances of thousands of characters each.
     */
    private fun universeVocabulary(store: SourceStore, facet: FacetDef, only: String? = null): Vocabulary? {
        val type = stores.typeOf(store.id)
        if (!type.type.universes) return null
        return when (facet.key) {
            // The kinds, the universes, the characters and the pairings are named by the dictionary, whatever the source ([UniverseNames])
            UniverseFacets.KIND -> Vocabulary(UniverseFacet.KINDS, null)
            // Every universe of the dictionary, linked to a work or not yet — to a work not known to have none
            UniverseFacets.UNIVERSE -> {
                val kinds = HashMap<String, List<String>>()
                store.items.forEachFacet(UniverseFacets.KIND) { id, keys -> kinds[id] = keys }
                store.corrections.allFacets().filter { it.facet == UniverseFacets.KIND }.groupBy { it.itemId }.forEach { (id, own) ->
                    kinds[id] = Corrected.facets(mapOf(UniverseFacets.KIND to kinds[id].orEmpty()), own)[UniverseFacets.KIND].orEmpty()
                }
                val universes = type.universes.all()
                val all = universes.associate { it.value to it.name }
                // Realism, an alternative history and an original work: our world, ours changed, or a world of its own
                fun original(id: String) = kinds[id].orEmpty().let { it.isNotEmpty() && it.all { k -> k in UniverseFacets.UNIVERSELESS } }
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
                // A universe is named by itself or by a character of it
                val naming = Naming(Mentions(names), valuesOf = { value -> ofCharacter[value] ?: setOf(value).filter { it in all } })
                // Every name of a universe and of each of its characters is a text that names it, and no other universe
                val hintNames = HashMap<String, MutableList<String>>()
                names.forEach { (value, own) -> (ofCharacter[value] ?: setOf(value).filter { it in all }).forEach { u -> hintNames.getOrPut(u) { ArrayList() } += own } }
                Vocabulary(
                    all,
                    allowed = { id -> if (original(id)) emptySet() else all.keys },
                    naming = { id -> naming.takeUnless { original(id) } },
                    hintNames = hintNames.mapValues { it.value.distinct() },
                    hintAmong = { all.keys },
                )
            }
            // The main characters and the secondary ones alike, each without what the other has
            UniverseFacets.CHARACTERS, UniverseFacets.SECONDARY_CHARACTERS -> {
                // Every character of every universe — by all its names: the fans write the one they like —
                // and the original characters; a work is given those of its own universes. Of one item,
                // the characters of its universes alone: no other is kept of it ([FacetData.load])
                val universes = workUniverses(store, only)
                val read = type.universes.all().let { all -> if (only == null) all else all.filter { it.value in universes[only].orEmpty() } }
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
                val out = leftOut(store, facet.key, only)
                fun of(id: String) = universes[id].orEmpty().flatMap { ofUniverse[it].orEmpty() }.toSet() - out(id)
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
                    // The characters of a work's universes, counted in its texts: a work of none is not counted
                    naming = { id -> of(id).takeIf { it.isNotEmpty() }?.let { own -> Naming(Mentions(own.associateWith { names[it].orEmpty() })) } },
                    // A work's tags tell the sex of its main characters
                    groupings = { list -> listOf(ValueGrouping("sex", CharacterSex.entries.map { it.name }, list.map { c -> sexes[c]?.let { intArrayOf(it.ordinal) } ?: IntArray(0) })) },
                )
            }
            UniverseFacets.PAIRINGS -> {
                // Every pair of a work's characters: the confirmed ones and those the model gives it
                val characters = linked(store, UniverseFacets.CHARACTERS, only).toMutableMap()
                chancesOf(store, UniverseFacets.CHARACTERS, only).forEach { (id, chances) ->
                    val given = ModelValues.given(chances[UniverseFacets.CHARACTERS].orEmpty())
                    if (given.isNotEmpty()) characters[id] = characters[id].orEmpty() + given
                }
                val rejected = answersOf(store, only).filter { it.facet == UniverseFacets.CHARACTERS && !it.added }
                    .groupBy({ it.itemId }, { it.key })
                // The original characters pair with anyone: a work need not list them among its main ones
                val pairs = characters.mapValues { (id, own) ->
                    val kept = (own + UniverseFacet.ORIGINAL_CHARACTERS.keys - rejected[id].orEmpty().toSet()).distinct().sorted()
                    kept.flatMapIndexed { k, a -> kept.drop(k + 1).map { b -> UniverseFacet.pairing(a, b) } }.toSet()
                }
                val names = UniverseFacet.ORIGINAL_CHARACTERS +
                    store.names(UniverseFacets.CHARACTERS, pairs.values.flatten().flatMap { p -> UniverseFacet.members(p)?.toList().orEmpty() })
                val values = pairs.values.flatten().toSet().associateWith { p ->
                    UniverseFacet.members(p)!!.let { (a, b) -> "${names[a] ?: a} / ${names[b] ?: b}" }
                }
                // Their names alone are what the model reads; shown with the sign of their characters' sexes ([UniverseNames])
                val sexes = UniverseFacet.sexes(type.universes)
                // Every name of a character of the dictionary, for counting two of them named together
                val known = HashMap<String, List<String>>()
                type.universes.all().forEach { u -> type.universes.characters(u.catalogue, u.id).forEach { known[UniverseFacet.valueOf(u.catalogue, it.id)] = it.names } }
                // The sexes of a pairing's two characters, and whether they are one sex: two groupings
                val kinds = CharacterSex.entries.flatMap { x -> CharacterSex.entries.filter { it >= x }.map { x to it } }
                // A pairing of two characters of the dictionary is named "A / B"; it names no other pairing of either of them
                val ofMember = HashMap<String, MutableSet<String>>()
                values.keys.forEach { p -> UniverseFacet.members(p)?.toList()?.forEach { ofMember.getOrPut(it) { HashSet() } += p } }
                val named = values.filterKeys { p -> UniverseFacet.members(p)?.toList()?.none { it in UniverseFacet.ORIGINAL_CHARACTERS } == true }
                Vocabulary(
                    values,
                    hintNames = named.mapValues { listOf(it.value) },
                    // Its own among them: a pairing is one of each of its characters'
                    hintAmong = { p -> UniverseFacet.members(p)?.toList().orEmpty().flatMap { ofMember[it].orEmpty() }.toSet() },
                    allowed = { id -> pairs[id].orEmpty() },
                    naming = { id ->
                        // The original characters have no names to count
                        val own = characters[id].orEmpty().distinct() - UniverseFacet.ORIGINAL_CHARACTERS.keys
                        if (own.size < 2) null else Naming(Mentions(own.associateWith { known[it] ?: listOfNotNull(names[it]) }), pairing = UniverseFacet::pairing)
                    },
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
     * The characters left out of the [facet] of every item ([UniverseFacet.leftOut]): those of its
     * other facet of characters — the user's answers, and the main ones the model gives it. The
     * main characters are worked out before the secondary ones: their chances are current here.
     */
    private fun leftOut(store: SourceStore, facet: String, only: String? = null): (String) -> Set<String> {
        if (UniverseFacet.otherCharacters(facet) == null) return { emptySet() }
        val answers = answersOf(store, only).filter { it.facet in UniverseFacet.CHARACTER_FACETS }.groupBy { it.itemId }
        val given = if (facet == UniverseFacets.SECONDARY_CHARACTERS)
            chancesOf(store, UniverseFacets.CHARACTERS, only).mapValues { ModelValues.given(it.value[UniverseFacets.CHARACTERS].orEmpty()) }
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
     * confirmed once the user said the work has no other universes.
     */
    private fun workUniverses(store: SourceStore, only: String? = null): Map<String, List<String>> {
        val answers = answersOf(store, only).filter { it.facet == UniverseFacets.UNIVERSE }.groupBy { it.itemId }
        val complete = if (only == null) store.corrections.allComplete() else mapOf(only to store.corrections.completeOf(only))
        val given = chancesOf(store, UniverseFacets.UNIVERSE, only)
        return (answers.keys + given.keys).associateWith { id ->
            val own = answers[id].orEmpty()
            val confirmed = own.filter { it.added }.map { it.key }
            if (UniverseFacets.UNIVERSE in complete[id].orEmpty()) confirmed
            else (confirmed + ModelValues.given(given[id]?.get(UniverseFacets.UNIVERSE).orEmpty()) - own.filter { !it.added }.map { it.key }.toSet()).distinct()
        }
    }

    /** The values of a facet the user linked every item — the item [only], when given — to: item to values. */
    private fun linked(store: SourceStore, facet: String, only: String? = null): Map<String, List<String>> =
        answersOf(store, only).filter { it.facet == facet && it.added }.groupBy({ it.itemId }, { it.key })

    /** The user's answers on the facets of every item, or of the item [only]. */
    private fun answersOf(store: SourceStore, only: String?): List<FacetCorrection> =
        if (only == null) store.corrections.allFacets() else store.corrections.facetsOf(only)

    /** The chances of the [facet] of every item, or of the item [only]: of a work of a large universe the chances of thousands of characters each. */
    private fun chancesOf(store: SourceStore, facet: String, only: String?): Map<String, Map<String, Map<String, Chance>>> =
        if (only == null) store.suggestions.ofFacets(listOf(facet)) else store.suggestions.ofItems(listOf(only), facets = listOf(facet))

    /**
     * The values to suggest for an item, every facet the source suggests values of, read now with
     * the user's corrections on top: a value the user has answered on is left out. An item that
     * never had chances gets them now; one whose data changed is refreshed in the background.
     */
    fun ofItem(store: SourceStore, itemId: String): List<ItemFacetSuggestions> {
        val facets = facetsOf(store)
        if (facets.isEmpty() || store.items.find(itemId) == null) return emptyList()
        val corrections = store.corrections.facetsOf(itemId)
        val site = store.items.facets(itemId)
        ensure(store, itemId, facets, site, corrections)
        val chances = store.suggestions.ofItems(listOf(itemId))[itemId].orEmpty()
        return facets.filter { it.suggest && !it.infer }.map { facet ->
            val answered = corrections.filter { it.facet == facet.key }.map { it.key }.toSet()
            val current = site[facet.key].orEmpty().toSet()
            // Of a facet of one value the work has — the site's or the user's — none other is suggested
            val held = facet.single && (current.isNotEmpty() || corrections.any { it.facet == facet.key && it.added })
            val suggested = if (held) emptyList() else chances[facet.key].orEmpty().values
                .filter { !it.had && it.key !in answered && it.key !in current && it.chance > ModelValues.LIKELY }.sortedByDescending { it.chance }
            ItemFacetSuggestions(facet, suggested, store.names(facet.key, suggested.map { it.key }))
        }
    }

    /**
     * The passages of the item's texts that name the [value] of the [facet], as its mentions were
     * counted: a character by its names, a universe by its own and its characters', a pairing by
     * its two named in one paragraph. Null for a facet whose values are not counted in the texts.
     */
    fun quotes(store: SourceStore, itemId: String, facet: String, value: String): List<Quote>? {
        val def = facetsOf(store).find { it.key == facet } ?: return null
        val naming = universeVocabulary(store, def, itemId)?.naming ?: return null
        return naming(itemId)?.quotes(store.workPassages(itemId), value).orEmpty()
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
        val def = facets.find { it.key == facet } ?: return null
        if (store.items.find(itemId) == null) return null
        // The chances being made after the user's answers are waited for, not made a second time beside them
        settled(store, itemId)
        val corrections = store.corrections.facetsOf(itemId)
        val site = store.items.facets(itemId)
        // The facet and those worked out before it, its values made of theirs: not the long fittings after it
        ensure(store, itemId, facets.take(facets.indexOf(def) + 1), site, corrections, now = true)
        // Only the likeliest are kept: every other made now — of a work's universes, characters and
        // pairings, those it may have alone
        val vocabulary = universeVocabulary(store, def, itemId) ?: examplesVocabulary(store, def)
        val may = vocabulary?.allowed?.invoke(itemId)
        val chances = everyChance(store, def, itemId, vocabulary)?.filter { may == null || it.key in may || it.had }
            ?: store.suggestions.ofItems(listOf(itemId))[itemId].orEmpty()[facet].orEmpty().values
        val answered = corrections.filter { it.facet == facet }.map { it.key }.toSet()
        val current = site[facet].orEmpty().toSet()
        // A character of the work's other facet of characters is no candidate here; nor a tag under
        // one the user said the work has not — it has none of them ([TagHierarchy])
        val hierarchy = store.hierarchies()[facet]
        // Nor a tag the user ignores: never offered
        val out = leftOut(store, facet, itemId)(itemId) +
            corrections.filter { it.facet == facet && !it.added }.flatMap { hierarchy?.descendants(it.key).orEmpty() } +
            store.ignoredTags()[facet].orEmpty()
        // A value the model gives the work is the work's already
        val scored = chances.filter { !it.had && it.key !in answered && it.key !in current && it.key !in out && !(def.infer && it.chance > ModelValues.LIKELY) }
            .map { FacetCandidate(it.key, it.chance, it.mentions) }
        val unscored = if (facet !in UniverseFacet.CHARACTER_FACETS || chances.any { !it.had } || facet in store.corrections.completeOf(itemId)) emptyList() else {
            val named = vocabulary?.mentions(store, itemId)?.invoke(itemId)
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

    /**
     * The user [answered] on a facet of the item: its chances of that facet and of the facets worked
     * out after it — made of its values — are made current by the weights fitted last, apart from
     * the answer, which is not kept waiting for them. Answers that come while they are made are
     * gathered: the making under way is given up at its next stage — what it read is stale — and
     * made once more for them all, from the earliest facet any of them was on. The whole source
     * is refreshed in the background, after its quiet ([changed]).
     */
    fun answered(store: SourceStore, itemId: String, answered: String) {
        val facets = facetsOf(store).filter { it.infer }
        // An answer on either facet of characters leaves a character out of the other: both made current
        val first = if (answered in UniverseFacet.CHARACTER_FACETS) UniverseFacets.CHARACTERS else answered
        val from = facets.indexOfFirst { it.key == first }
        if (from < 0) return
        val key = "${store.id}/$itemId"
        synchronized(answering) {
            val work = answering.getOrPut(key) { Answering() }
            work.generation++
            work.from = minOf(work.from, from)
            if (!work.running) {
                work.running = true
                answers.execute { makeCurrent(store, itemId, key, work) }
            }
        }
    }

    /**
     * Waits until the item's chances are made current after every answer on it so far; at once when
     * none is being made. Never longer than [SETTLE_WAIT_SECONDS]: the chances of a work of a great
     * universe behind those of other works may take long, and a page is not held for them — it shows
     * the chances as they are, the newer come with the next reading. True when they are current.
     */
    fun settled(store: SourceStore, itemId: String): Boolean {
        val waiting = synchronized(answering) { answering["${store.id}/$itemId"]?.settled } ?: return true
        return try {
            waiting.get(SETTLE_WAIT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (e: TimeoutException) {
            log.info("{}: chances of {} still being made after {} s: read as they are", store.id, itemId, SETTLE_WAIT_SECONDS)
            false
        } catch (e: ExecutionException) {
            false
        } catch (e: CancellationException) {
            false
        }
    }

    /**
     * The making of an item's chances after the user's answers: until no answer came while it was
     * made. Whatever ends it — an error of the JVM too, out of memory on a great task — the work is
     * free after: no answer on it is left unmade for good, no reader waits for it for ever.
     */
    private fun makeCurrent(store: SourceStore, itemId: String, key: String, work: Answering) {
        try {
            workload.run(Work("workChances", source = store.id, item = itemId), mapOf("answer" to 1)) { makeCurrentRounds(store, itemId, key, work) }
        } catch (e: Throwable) {
            log.error("{}: chances of {} were not made", store.id, itemId, e)
            synchronized(answering) {
                if (answering[key] === work) answering.remove(key)
                work.running = false
                work.settled.completeExceptionally(e)
            }
            if (e is Error) throw e
        }
    }

    private fun makeCurrentRounds(store: SourceStore, itemId: String, key: String, work: Answering) {
        while (true) {
            val (generation, from) = synchronized(answering) { (work.generation to work.from).also { work.from = Int.MAX_VALUE } }
            val stop = { synchronized(answering) { work.generation != generation } }
            val started = System.currentTimeMillis()
            val made = try {
                val facets = facetsOf(store).filter { it.infer }
                if (store.items.find(itemId) != null) {
                    // From the facet answered on round the circle: every facet the model works out reads the others
                    ensure(store, itemId, facets.drop(from) + facets.take(from), store.items.facets(itemId), store.corrections.facetsOf(itemId), now = true, stop = stop)
                }
                true
            } catch (e: CancellationException) {
                false
            } catch (e: Exception) {
                log.warn("{}: chances of {} were not made: {}", store.id, itemId, e.message, e)
                true
            }
            synchronized(answering) {
                if (!made) work.from = minOf(work.from, from)
                if (work.generation == generation) {
                    log.info("{}: chances of {} made current after the answers in {} ms", store.id, itemId, System.currentTimeMillis() - started)
                    answering.remove(key)
                    work.running = false
                    work.settled.complete(Unit)
                    return
                }
            }
        }
    }

    /**
     * An item's chances being made current after the user's answers: how many answers there were
     * ([generation] — a making begun before the last one is stale), the earliest facet answered
     * on not yet made, whether it is being made, and the waiters for its end.
     */
    private class Answering {
        var generation = 0L
        var from = Int.MAX_VALUE
        var running = false
        val settled = CompletableFuture<Unit>()
    }

    /**
     * The item's chances of the facets the model gives values of: made now when it has none, else
     * refreshed in the background when what they were made of changed.
     */
    fun ensureFor(store: SourceStore, itemId: String) {
        val facets = facetsOf(store).filter { it.infer }
        if (facets.isEmpty() || store.items.find(itemId) == null) return
        // The chances being made after the user's answers are waited for, not made a second time beside them
        settled(store, itemId)
        ensure(store, itemId, facets, store.items.facets(itemId), store.corrections.facetsOf(itemId))
    }

    /**
     * The item's chances made now when it has none — or when what they were made of changed and
     * the caller waits for them ([now]: the user just answered and chooses next); otherwise
     * refreshed in the background.
     */
    private fun ensure(
        store: SourceStore, itemId: String, facets: List<FacetDef>, site: Map<String, List<String>>, corrections: List<FacetCorrection>,
        now: Boolean = false, stop: () -> Boolean = { false },
    ) {
        val encoder = plugins.textEncoder() ?: return
        val fingerprint = FacetData.fingerprint(
            site, corrections, store.textVectors.hashes(encoder.id, itemId).values, store.parts.windowCount(itemId, encoder.id),
            store.corrections.completeOf(itemId), store.drafts.ofItems(listOf(itemId))[itemId].orEmpty(),
        )
        val known = facets.associateWith { store.suggestions.fingerprint(itemId, it.key) }
        val stale = facets.filter { known[it] != fingerprint }
        if (stale.isEmpty()) return
        if (now || stale.any { known[it] == null }) refreshNow(store, itemId, stale, stop)
        // The change that made them stale was told already: a refresh under way is not given up for it
        else changed(store.id, "stale", newer = false)
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
        answers.shutdownNow()
        // Nobody waits for chances that will not be made
        synchronized(answering) { answering.values.forEach { it.settled.cancel(false) } }
    }

    companion object {
        /** The quiet after the last change before a source is refreshed: a page sends itself several times as it grows. */
        private const val DELAY_SECONDS = 5L

        /** The longest a reader waits for a work's chances made after the answers ([settled]). */
        private const val SETTLE_WAIT_SECONDS = 30L

        /** The environment variable naming the folder every task of a whole facet is kept in ([SuggestionTaskFile]); unset, none is. */
        private const val TASKS_FOLDER = "RECOMMEND4ME_TASKS"

        /** Items scored at once and written in one transaction: memory, not meaning. */
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
    val fingerprints: LongArray,
    /** The fingerprint of the whole facet: of every item's. */
    val basis: Long,
) {
    companion object {
        /**
         * The rule of what is kept of an item's chances: 2 — the likeliest others offered besides the
         * likely ones; 3 — the rejected ones too; 4 — the site's examples too; 5 — of a facet of
         * limited values, not every value the work may have: the likely and the offered alone.
         */
        private const val KEPT = 5

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
         * [only]: THE TASK OF THAT ITEM ALONE — one row, judged by the weights fitted last, which read
         * a task by the keys of its values, its context and its blocks: its inputs, the other facets'
         * values the model gave it, its context and its fingerprint are read of it alone, not of every
         * item — of a work of a large universe the chances of thousands of characters each. The values
         * chosen from are every value still. No [basis]: what the weights were fitted on is told by the
         * background refresh, which reads every item.
         */
        fun load(
            store: SourceStore, facet: FacetDef, encoder: TextEncoder, known: Map<String, String> = emptyMap(),
            imageEncoder: String? = null, sets: Map<SetKind, Long> = emptyMap(), only: String? = null,
            mentions: ((String) -> Map<String, Int>?)? = null,
            allowed: ((String) -> Set<String>)? = null,
            groupings: ((List<String>) -> List<ValueGrouping>)? = null,
            examples: ((String) -> List<String>)? = null,
            lackWeight: Double = 1.0,
            hintNames: Map<String, List<String>> = emptyMap(),
            hintAmong: (String) -> Collection<String> = { emptyList() },
        ): FacetData {
            val schema = store.schema
            // Every item's values make the values chosen from; the rows of the task are of every item, or of [only]
            val every = store.items.keys().map { it.id }
            val ids = if (only == null) every else listOf(only)
            val site = HashMap<String, HashMap<String, MutableList<String>>>()
            store.items.forEachFacetValue { id, f, key -> site.getOrPut(id) { HashMap() }.getOrPut(f) { ArrayList() } += key }
            val corrections = store.corrections.allFacets().groupBy { it.itemId }
            // Of a facet of tags, the user's answers with what they say of the tags above and under them
            val hierarchy = store.hierarchies()[facet.key]
            val answers = if (hierarchy == null) corrections else corrections.mapValues { hierarchy.expand(facet.key, it.value) }
            val within = hierarchy?.let { mapOf(facet.key to it) }.orEmpty()
            val complete = store.corrections.allComplete()
            // The examples are the facet's values as far as the model learns, the user's answers over
            // them — the facet the user corrects; the site's facet they come from is among the item's inputs
            // And the drafts of the layer ([LayerDrafts][io.github.vlsergey.recommend4me.draft.LayerDrafts]): values the item
            // has unless the user said otherwise, learnt from with less weight ([DRAFT_WEIGHT]) — they are no one's word
            val drafts = store.drafts.all()
            val drafted = drafts.mapValues { (_, list) -> list.filter { it.facet == facet.key }.map { it.key } }.filterValues { it.isNotEmpty() }
            val learnt = if (examples == null && drafted.isEmpty()) site else HashMap(site).also { all ->
                every.forEach { id ->
                    val own = examples?.invoke(id).orEmpty() + drafted[id].orEmpty()
                    if (own.isNotEmpty()) all[id] = HashMap(site[id].orEmpty()).apply { this[facet.key] = (this[facet.key].orEmpty() + own).distinct().toMutableList() }
                }
            }
            val corrected = every.associateWith { id -> TagHierarchy.corrected(learnt[id].orEmpty(), corrections[id].orEmpty(), within) }

            // Every value of the facet the site or the user gave any item, and every value known besides
            val everyValue = (corrected.values.flatMap { it[facet.key].orEmpty() } +
                answers.values.flatten().filter { it.facet == facet.key }.map { it.key } + known.keys).distinct().sorted()
            // Of one item of a facet whose values a work may have are limited ([allowed]: a work's
            // characters are of its universes), those it may have and its own alone: no other is
            // kept of it — of a work of two universes some thousand characters, not every one
            val values = if (only == null || allowed == null) everyValue else {
                val own = allowed(only) + corrected[only]?.get(facet.key).orEmpty() +
                    answers[only].orEmpty().filter { it.facet == facet.key }.map { it.key } + drafted[only].orEmpty() + examples?.invoke(only).orEmpty()
                everyValue.filter { it in own }
            }
            val index = values.withIndex().associate { (i, k) -> k to i }

            // The other facets' values as the context — of the facets the source has now, the site's own among them.
            // THE FACETS THE MODEL WORKS OUT READ EACH OTHER, IN A CIRCLE — the universes, the main and the
            // secondary characters, the pairings, the tags: of every other one, the values the model gives
            // the item besides the site's and the user's, as it gave them last; the tags with those above them
            val others = schema.facets.filter { it.infer && it.key != facet.key }.map { it.key }
            val modelled = when {
                others.isEmpty() -> emptyMap()
                only != null -> store.suggestions.ofItems(ids, facets = others)
                else -> store.suggestions.ofFacets(others)
            }
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
            val ownLine = facet.original?.takeIf { site.values.any { facet.key in it } }
            val inputs = inputs(store, ids, encoder, imageEncoder, sets, only, leaveOut = setOfNotNull(ownLine))

            val names = Matrix(values.size, d)
            nameVectors(store, facet.key, values, corrections.values.flatten(), known, encoder).forEach { (key, v) ->
                index[key]?.let { names.held.put(v, 0, names.row(it), d) }
            }

            fun indices(list: Collection<String>) = list.mapNotNull { index[it] }.distinct().toIntArray()
            val grouped = groupings?.invoke(values).orEmpty()
            // An item whose values of the facet are all named: every other value rejected by the user's word
            val allNamed = BooleanArray(ids.size) { facet.key in complete[ids[it]].orEmpty() }
            val assigned = ids.map { indices(corrected[it]?.get(facet.key).orEmpty()) }
            // What is learnt from, not what is judged: no names when the item [only] is judged by the weights fitted last
            val (hintValues, hintTexts) = if (only != null) emptyList<String>() to Matrix(0, 0) else hints(store, facet.key, hintNames, encoder)
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
            val hashes = HashMap<String, MutableList<String>>()
            val windowCounts = if (only == null) {
                store.textVectors.hashes(encoder.id).forEach { (k, hash) -> hashes.getOrPut(k.first) { ArrayList() } += hash }
                store.parts.windowCounts(encoder.id)
            } else {
                hashes[only] = store.textVectors.hashes(encoder.id, only).values.toMutableList()
                mapOf(only to store.parts.windowCount(only, encoder.id))
            }
            val fingerprints = LongArray(ids.size) { i ->
                val id = ids[i]
                fingerprint(site[id].orEmpty(), corrections[id].orEmpty(), hashes[id].orEmpty(), windowCounts[id] ?: 0, complete[id].orEmpty(), drafts[id].orEmpty())
            }
            // The item's own values: neither the site's examples nor the drafts
            val held = if (examples == null && drafted.isEmpty()) task.assigned else ids.map { id ->
                indices(TagHierarchy.corrected(site[id].orEmpty(), corrections[id].orEmpty(), within)[facet.key].orEmpty())
            }
            // The task of one item tells nothing of what the weights were fitted on
            if (only != null) return FacetData(ids, values, task, held, allNamed, fingerprints, NO_BASIS)
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
            return FacetData(ids, values, task, held, allNamed, fingerprints, basis)
        }

        /**
         * Every input of the [ids], as the model of the grades reads them: a block of every text
         * that has vectors (the title, the description, the notes, the site's lines), of every
         * picture block, of every set (the reviews, the windows of the chapters, the screenshots).
         * Of the item [only] alone when given — no other item's chances are made — the rows of
         * the others left empty.
         */
        private fun inputs(
            store: SourceStore, ids: List<String>, encoder: TextEncoder, imageEncoder: String?, sets: Map<SetKind, Long>, only: String?,
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
                store.textVectors.ofMany(listOf(only), encoder.id)[only]?.forEach { (key, v) -> texts[key]?.let { put(only, it, v) } }
                imageEncoder?.let { e -> store.pictures.itemVectorsOf(listOf(only), e)[only]?.forEach { (block, v) -> put(only, block, v) } }
                store.setVectors.ofMany(listOf(only), sets)[only]?.forEach { (block, v) -> put(only, block, v) }
            }
            return blocks.map { (name, b) -> InputBlock(name, b.first, b.second) }
        }

        /** The vectors of the values' names as queries — of [known]'s texts for those it has — encoded now where missing or the name changed. */
        private fun nameVectors(
            store: SourceStore, facet: String, values: List<String>, corrections: List<FacetCorrection>, known: Map<String, String>, encoder: TextEncoder,
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
                if (have != null && have.first == Vectors.hash(name)) out[key] = have.second else todo += key to name
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
