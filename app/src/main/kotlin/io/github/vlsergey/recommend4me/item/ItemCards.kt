package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.api.model.ItemSummary
import io.github.vlsergey.recommend4me.api.model.LinkedItem
import io.github.vlsergey.recommend4me.api.model.Prediction
import io.github.vlsergey.recommend4me.api.model.SearchMatch
import io.github.vlsergey.recommend4me.api.model.TextRange
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.Chance
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.work.TextPlace
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component
import java.time.ZoneOffset

/** The grade of the current version of a work, and the latest grade of an earlier one with its version. */
data class Graded(val grade: Int?, val previousGrade: Int?, val previousVersion: String?)

/** The cards of works as the list shows them, read in batch: one query per table per source. */
@Component
class ItemCards(private val stores: Stores, private val works: Works) {

    /** The grades of the items [ids] of a source at their [versions]. */
    fun graded(ratings: List<Rating>, versions: Map<String, String>): Map<String, Graded> =
        ratings.groupBy { it.itemId }.mapNotNull { (id, list) ->
            val version = versions[id] ?: return@mapNotNull null
            val current = list.filter { it.version == version }.maxByOrNull { it.ratedAt }
            val earlier = list.filter { it.version != version }.maxByOrNull { it.ratedAt }
            id to Graded(current?.grade, earlier?.grade, earlier?.version)
        }.toMap()

    /**
     * The facets of an item for the interface: every value with its name, the user's word on it,
     * the model's chance of it, and whether the model gave it; [texts] give the site's own line of
     * a facet that has one ([FacetDef.original][io.github.vlsergey.recommend4me.source.FacetDef.original]).
     */
    fun facets(
        store: SourceStore,
        site: Map<String, List<String>>,
        chances: Map<String, Map<String, Chance>>?,
        corrections: List<FacetCorrection>,
        names: Map<String, Map<String, String>>,
        texts: Map<String, String> = emptyMap(),
        /** Every facet asked for, one the work has no values of included: a page adds values to it. */
        empty: Boolean = false,
        /** The facets the user said the work's values of are all named: none offered besides. */
        complete: Set<String> = emptySet(),
        only: (String) -> Boolean,
    ): List<ItemFacet> {
        val schema = store.schema
        val base = ModelValues.of(schema, site, chances)
        // A tag under another gives the work the other too, shown as such; a tag the user said it has
        // not hides every tag under it ([TagHierarchy]) — read only of the facets of tags asked for
        val ofTags = schema.facets.any { only(it.key) && it.key in store.tagFacets }
        val hierarchies = if (!ofTags) emptyMap() else store.hierarchies()
        // The tags the user ignores: never given or offered to mark — shown only where the user answered on them
        val ignoredTags = if (!ofTags) emptyMap() else store.ignoredTags()
        val single = schema.facets.filter { it.single }.map { it.key }.toSet()
        val plain = Corrected.facets(base, corrections, single)
        val corrected = TagHierarchy.corrected(base, corrections, hierarchies)
        val said = corrections.filter { it.added }.groupBy({ it.facet }, { it.key })
        return schema.facets.filter { only(it.key) }.mapNotNull { def ->
            val hierarchy = hierarchies[def.key]
            val negated = hierarchy?.let { h -> corrections.filter { it.facet == def.key && !it.added }.flatMap { listOf(it.key) + h.descendants(it.key) }.toSet() }.orEmpty()
            val implied = corrected[def.key].orEmpty().toSet() - plain[def.key].orEmpty().toSet()
            // A character of the work's other facet of characters is not shown here at all
            val out = UniverseFacet.leftOut(def.key, { corrected[it].orEmpty() }, { said[it].orEmpty() })
            val siteKeys = site[def.key].orEmpty().toSet()
            val fromSite = siteKeys + def.examplesFrom?.let { from ->
                val keys = site[from].orEmpty()
                val bound = store.tags.bindingsOf(store.id)
                keys.mapNotNull { bound[it]?.let(TagRepository::key) }
            }.orEmpty()
            val answered = corrections.filter { it.facet == def.key }.map { it.key }.toSet()
            val ignored = ignoredTags[def.key].orEmpty()
            /** A value shown to mark: none the user ignores, the site's or not, but those answered on. */
            fun offerable(key: String) = key !in ignored || key in answered
            val keys = (corrected[def.key].orEmpty() - out).filter(::offerable)
            val had = base[def.key].orEmpty().toSet()
            // Every value the user rejected is shown crossed out — offered or given, it stays in its place
            val removed = corrections.filter { it.facet == def.key && !it.added && it.key !in out }.map { it.key }
            val added = corrections.filter { it.facet == def.key && it.added }.associate { it.key to it.name }
            val original = def.original?.let { texts[it] }
            // The chances of a facet the model works on now: none of one it no longer does
            val own = if (def.suggest || def.infer) chances?.get(def.key).orEmpty() else emptyMap()
            // Of a facet of the layer the model works out: every value the site gives the work — a tag of
            // the site bound to it — it is not shown with and the user has not answered on, whatever its
            // chance: the site's word, none hidden; then the likeliest values the work is not shown with,
            // the user has not answered on and the site does not give — to make at least so many
            // Of a facet of one value that has it, none: another one added takes its place
            val offered = if (!(def.infer && def.editable) || def.key in complete || (def.single && keys.isNotEmpty())) emptyList() else {
                val shown = keys.toSet()
                val bySite = fromSite.filter { it !in shown && it !in answered && it !in out && it !in negated && it !in ignored }
                    .sortedByDescending { own[it]?.chance ?: -1.0 }
                bySite + ModelValues.offered(
                    own.filterValues { !it.had && it.key !in out && it.key !in negated && it.key !in ignored }.mapValues { it.value.chance },
                    shown = shown, answered = answered, fromSite = fromSite,
                )
            }
            // A facet said to have no other values is shown with none too: the answer is there to take back
            if (!empty && keys.isEmpty() && removed.isEmpty() && offered.isEmpty() && original == null && def.key !in complete) return@mapNotNull null
            // The value's own name — a tag's as the user and the sites name it — the name the answer was given with only of a value nothing else names
            // The names of the values offered the caller did not read — a site's tags bound to tags of the layer — in one read
            val unnamed = offered.filter { names[def.key]?.containsKey(it) != true }
            val more = if (unnamed.isEmpty()) emptyMap() else store.names(def.key, unnamed)
            fun name(key: String) = names[def.key]?.get(key) ?: more[key] ?: added[key] ?: key
            // What the catalogue says of a universe or a character shown: one read of the facet's
            val described = store.descriptions(def.key, keys + offered + removed)
            fun info(key: String, corrected: FacetValueInfo.Corrected?, offer: Boolean = false): FacetValueInfo {
                // Drafted, unanswered: the site's value it was drafted from, not the model's word
                val draftedFrom = chances?.get(def.key)?.get(key)?.draftedFrom?.takeIf { corrected == null && !offer }
                return FacetValueInfo(
                    key = key,
                    name = name(key),
                    nameKey = store.nameKey(def.key, key),
                    corrected = corrected,
                    chance = own[key]?.chance,
                    inferred = (key in had && key !in siteKeys && draftedFrom == null).takeIf { it },
                    draftedFrom = draftedFrom,
                    offered = offer.takeIf { it },
                    implied = (key in implied).takeIf { it },
                    description = described[key],
                )
            }
            ItemFacet(
                facet = def.key,
                labelKey = I18nKeys.facetLabel(store.id, def),
                editable = def.editable,
                tags = (def.key in store.tagFacets).takeIf { it },
                // In the order shown: the likeliest first — a value with no chance after them — and a
                // tag above another before it ([TagHierarchy.parentsFirst]); the pages keep it
                propertyValues = (keys.map { k ->
                    // Confirmed: the site or the model gave it — the model even when the user's answer
                    // has since made it the work's own, no longer the model's to give
                    val given = k in had || (own[k]?.chance ?: 0.0) > ModelValues.LIKELY
                    info(k, if (k in added) (if (given) FacetValueInfo.Corrected.CONFIRMED else FacetValueInfo.Corrected.ADDED) else null)
                } + offered.map { info(it, null, offer = true) } + removed.map { info(it, FacetValueInfo.Corrected.REMOVED) })
                    .sortedByDescending { it.chance ?: -1.0 }
                    .let { values -> hierarchy?.parentsFirst(values) { it.key } ?: values },
                original = original,
                complete = (def.key in complete).takeIf { it },
                single = def.single.takeIf { it },
                completable = def.completable.takeIf { it },
            )
        }
    }

    /**
     * The cards of [keys] of one content type, in their order; [matches] what a search found in
     * them, [places] where in the work each is ([Search.places][io.github.vlsergey.recommend4me.search.Search.places]).
     * Every source's items are read in one go.
     */
    fun cards(
        type: TypeStore,
        keys: List<ItemKey>,
        matches: Map<ItemKey, io.github.vlsergey.recommend4me.search.SearchMatch> = emptyMap(),
        places: Map<ItemKey, TextPlace> = emptyMap(),
        clusters: WorkClusters = works.of(type),
        /** The user's word on the works for the line searched, when searched. */
        judged: Map<ItemKey, Boolean> = emptyMap(),
    ): List<ItemSummary> {
        val out = HashMap<ItemKey, ItemSummary>()
        val members = keys.flatMap { clusters.members(it) }.distinct()
        // The heads of every member, to name the linked items
        val heads = members.groupBy { it.source }.flatMap { (source, list) ->
            val store = stores.source(source) ?: return@flatMap emptyList()
            store.items.heads(list.map { it.id }).map { (id, head) ->
                ItemKey(source, id) to (head to Corrected.title(head.title, store.corrections.fieldsOf(id)))
            }
        }.toMap()
        keys.groupBy { it.source }.forEach { (source, list) ->
            val store = stores.source(source) ?: return@forEach
            val ids = list.map { it.id }
            val facets = store.items.facetsOf(ids)
            val corrections = store.corrections.facetsOf(ids)
            val fields = store.corrections.fieldsOf(ids)
            val onCard = store.schema.facets.filter { it.onCard }.map { it.key }.toSet()
            // The chances of the facets on a card alone
            val chances = store.suggestions.ofItems(ids, facets = onCard)
            val names = onCard.associateWith { facet ->
                store.names(facet, ids.flatMap { facets[it]?.get(facet).orEmpty() + ModelValues.named(chances[it]?.get(facet)) + corrections[it].orEmpty().filter { c -> c.facet == facet }.map { c -> c.key } })
            }
            val numbers = store.items.numbersOf(ids)
            val pictures = store.pictures.countsOf(ids)
            val ratings = store.ratings.all().filter { it.itemId in ids.toSet() }
            val versions = ids.mapNotNull { id -> heads[ItemKey(source, id)]?.first?.let { id to it.version } }.toMap()
            val graded = graded(ratings, versions)
            val predicted = type.models.predicted(source)
            val heldOut = type.models.heldOut(source)
            val interest = store.interest.all()
            val signals = store.signals.all()
            ids.forEach { id ->
                val key = ItemKey(source, id)
                val (head, title) = heads[key] ?: return@forEach
                val g = graded[id]
                out[key] = ItemSummary(
                    source = source,
                    item = id,
                    work = clusters.representative(key).toString(),
                    title = title,
                    version = head.version,
                    url = head.url,
                    updatedAt = head.updatedAt.atOffset(ZoneOffset.UTC),
                    facets = facets(store, facets[id].orEmpty(), chances[id], corrections[id].orEmpty(), names) { it in onCard },
                    numbers = Corrected.numbers(numbers[id].orEmpty(), fields[id].orEmpty()),
                    hasCover = pictures[id]?.second ?: false,
                    pictureCount = pictures[id]?.first ?: 0,
                    linked = clusters.members(key).drop(1).mapNotNull { m ->
                        heads[m]?.let { (h, t) -> LinkedItem(m.source, m.id, t, h.url) }
                    },
                    // Its own: a linked item has the work's grade written to it ([LinkedGrades][io.github.vlsergey.recommend4me.rating.LinkedGrades])
                    grade = g?.grade,
                    previousGrade = g?.previousGrade,
                    previousGradeVersion = g?.previousVersion,
                    prediction = predicted[id]?.let { Prediction(it.score) },
                    heldOut = heldOut[id]?.let { Prediction(it) },
                    // Its own too: a linked item has the work's mark written to it ([LinkedInterest][io.github.vlsergey.recommend4me.interest.LinkedInterest])
                    interest = interest[id]?.interesting,
                    interestChance = predicted[id]?.interest,
                    searchMatch = matches[key]?.let { m ->
                        val place = places[key] ?: TextPlace()
                        SearchMatch(
                            byMeaning = m.byMeaning, text = m.text, highlights = m.highlights.map { (s, e) -> TextRange(s, e) },
                            labelKey = place.labelKey, title = place.title, part = place.part,
                        )
                    },
                    searchRelevant = judged[key],
                    signals = signals[id]?.takeIf { it.isNotEmpty() },
                )
            }
        }
        return keys.mapNotNull { out[it] }
    }
}
