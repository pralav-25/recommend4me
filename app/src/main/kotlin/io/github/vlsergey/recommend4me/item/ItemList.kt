package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.FacetValueUse
import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.search.Search
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.abs

enum class ItemView { UNRATED, RATED, ALL }
/** The orders of the list; ADDED — the newest in the application first, by when it first saw the work. */
enum class ItemOrder { SCORE, UPDATED, ADDED, NUMBER, SURPRISE }

/**
 * What a filter facet shows: a work with a value [shown] — when given, those alone, every other
 * value hidden, one a site adds later too — or else a value not [hidden], every other shown, one
 * added later too; a work without any value of the facet unless [withoutHidden].
 */
class FacetChoice(val facet: String, val hidden: Set<String>, val shown: Set<String>?, val withoutHidden: Boolean) {
    fun passes(keys: Collection<String>): Boolean = when {
        keys.isEmpty() -> !withoutHidden
        shown != null -> keys.any { it in shown }
        else -> keys.any { it !in hidden }
    }
}

/** The works whose number [numberId] lies within [from]..[to], both ends included; a null end is open. */
class NumberRange(val numberId: String, val from: Double?, val to: Double?) {
    operator fun contains(value: Double) = (from == null || value >= from) && (to == null || value <= to)

    companion object {
        /** "<number id>=<from>..<to>", an end left out open; null when it is not one. */
        fun parse(text: String): NumberRange? {
            val (id, range) = text.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() } ?: return null
            val (from, to) = range.split("..", limit = 2).takeIf { it.size == 2 } ?: return null
            // An end left out is open; one that is no number makes the whole no range
            val low = from.takeIf { it.isNotBlank() }?.let { it.trim().toDoubleOrNull() ?: return null }
            val high = to.takeIf { it.isNotBlank() }?.let { it.trim().toDoubleOrNull() ?: return null }
            return NumberRange(id, low, high)
        }
    }
}

/**
 * The list of the works of a content type. H2 joins no two files, so the list is put together here
 * for each request from what each file says, read in one batch per file and only the columns the
 * list needs: the items' keys and update times from the source databases, the values of the
 * filtered facets, the grades from the ratings databases, the predictions from the model database.
 * Nothing of it is kept after the request.
 */
@Component
class ItemList(
    private val stores: Stores,
    private val works: Works,
    private val cards: ItemCards,
    private val search: Search,
    private val recommendations: Recommendations,
) {

    /** A facet as the filters of a content type know it: the shared key of its sources' facets, or "<source>.<key>" — as the model does. */
    private fun filterId(store: SourceStore, facet: io.github.vlsergey.recommend4me.source.FacetDef) = FeatureNames.facetId(store.source, facet)

    /**
     * One row of the list before its card is read; [expected] — the prediction times the chance of
     * interest — is what the list is sorted by first, [number] what it is sorted by otherwise.
     */
    private class Row(val key: ItemKey, val updatedAt: Instant, val firstSeenAt: Instant, val graded: Boolean, val prediction: Double?, val expected: Double?, val number: Double?)

    fun page(
        typeId: String,
        view: ItemView,
        order: ItemOrder,
        sortNumber: String?,
        line: String?,
        facets: List<FacetChoice>,
        sources: Set<String>?,
        ranges: List<NumberRange>,
        offset: Int,
        limit: Int,
    ): ItemPage {
        val type = stores.type(typeId) ?: return ItemPage(0, emptyList())
        val clusters = works.of(type)
        val places = if (order == ItemOrder.SURPRISE) recommendations.gradePlaces(typeId) else null
        val found = line?.takeIf { it.isNotBlank() }?.let { search.search(typeId, it) }
        val relevance = found?.keys?.withIndex()?.associate { (i, k) -> k to i }
        val choices = facets.associateBy { it.facet }

        val rows = ArrayList<Row>()
        type.sources.filter { sources == null || it.id in sources }.forEach { s ->
            val keys = s.items.keys()
            val versions = keys.associate { it.id to it.version }
            val graded = cards.graded(s.ratings.all(), versions)
            val grades = graded.mapNotNull { (id, g) -> g.grade?.let { id to it } }.toMap()
            val predicted = type.models.predicted(s.id)
            val marks = s.interest.all()
            val numbers = if (order == ItemOrder.NUMBER && sortNumber != null) numberOf(s, sortNumber).orEmpty() else emptyMap()
            // How far the model that had not seen a graded work puts it from where the user's grade
            // stands: beyond the lowest grade's place for a work of it is no surprise, nor beyond the highest's
            val surprises = if (order == ItemOrder.SURPRISE && places != null) {
                val low = places.values.min()
                val high = places.values.max()
                type.models.heldOut(s.id).mapNotNull { (id, score) ->
                    val place = places[grades[id]] ?: return@mapNotNull null
                    id to when (place) {
                        low -> (score - place).coerceAtLeast(0.0)
                        high -> (place - score).coerceAtLeast(0.0)
                        else -> abs(score - place)
                    }
                }.toMap()
            } else emptyMap()
            val passes = facetFilter(s, choices)
            // The ranges of the numbers the source has, with their values: a source without one is not limited by it
            val limits = ranges.mapNotNull { r -> numberOf(s, r.numberId)?.let { r to it } }
            keys.forEach { k ->
                val key = ItemKey(s.id, k.id)
                if (relevance != null && key !in relevance) return@forEach
                if (passes != null && !passes(k.id)) return@forEach
                // A work without the number is not known to be within the range: not shown
                if (limits.any { (r, values) -> values[k.id]?.let { it !in r } ?: true }) return@forEach
                val p = predicted[k.id]
                // What the user said of the work decides its chance; the prediction alone without a model of interest
                val chance = Interest.chance(marks[k.id]?.interesting, p?.interest)
                // The score as the scale shows it — below its 0 it is 0: a chance multiplying a negative
                // score would put the likelier interesting work lower. Equal, the prediction decides
                val expected = p?.let { if (chance != null) it.score.coerceAtLeast(0.0) * chance else it.score }
                rows += Row(key, k.updatedAt, k.firstSeenAt, k.id in grades, p?.score, expected, numbers[k.id] ?: surprises[k.id])
            }
        }
        // An item is graded by its own grade: a linked one has the work's written to it ([LinkedGrades][io.github.vlsergey.recommend4me.rating.LinkedGrades])
        val viewed = rows.filter { row ->
            when (view) {
                ItemView.UNRATED -> !row.graded
                ItemView.RATED -> row.graded
                ItemView.ALL -> true
            }
        }
        val sorted = when {
            relevance != null -> viewed.sortedBy { relevance.getValue(it.key) }
            order == ItemOrder.SCORE -> viewed.sortedWith(
                compareByDescending<Row> { it.expected ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.prediction ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.updatedAt },
            )
            order == ItemOrder.NUMBER || order == ItemOrder.SURPRISE ->
                viewed.sortedWith(compareByDescending<Row> { it.number ?: Double.NEGATIVE_INFINITY }.thenByDescending { it.updatedAt })
            order == ItemOrder.ADDED -> viewed.sortedWith(compareByDescending<Row> { it.firstSeenAt }.thenByDescending { it.updatedAt })
            else -> viewed.sortedByDescending { it.updatedAt }
        }
        val pageKeys = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)).map { it.key }
        val matches = found?.let { search.matches(typeId, it, pageKeys) }.orEmpty()
        val judged = found?.let { type.searchJudgments.of(it.line) }.orEmpty()
        return ItemPage(total = sorted.size, items = cards.cards(type, pageKeys, matches, search.places(matches), clusters, judged))
    }

    /** The values of the number [numberId] (shared, or "<source>.<key>") of the source's items, as the user corrected them; null when the source has no such number. */
    private fun numberOf(s: SourceStore, numberId: String): Map<String, Double>? {
        val def = s.schema.numbers.firstOrNull { FeatureNames.numberId(s.source, it) == numberId } ?: return null
        val site = s.items.number(def.key)
        val overrides = s.corrections.allFields().mapNotNull { (id, fields) ->
            fields[Corrected.numberField(def.key)]?.toDoubleOrNull()?.let { id to it }
        }.toMap()
        return site + overrides
    }

    /**
     * Whether an item of the source shows by the filters: by every facet's [FacetChoice] the source
     * has. Null when no facet of the source is filtered.
     */
    private fun facetFilter(s: SourceStore, choices: Map<String, FacetChoice>): ((String) -> Boolean)? {
        val filtered = s.schema.facets.filter { it.filter }.mapNotNull { def -> choices[filterId(s, def)]?.let { def to it } }
        if (filtered.isEmpty()) return null
        val corrections = s.corrections.allFacets().groupBy { it.itemId }
        val values = filtered.map { (def, choice) -> valuesOf(s, def, corrections) to choice }
        return { id -> values.all { (byItem, choice) -> choice.passes(byItem[id].orEmpty()) } }
    }

    /** Every item's values of one facet as the user sees them: the site's, the model's of a facet it works out, the user's corrections on top. */
    private fun valuesOf(s: SourceStore, def: FacetDef, corrections: Map<String, List<FacetCorrection>>): Map<String, List<String>> {
        val byItem = HashMap<String, List<String>>()
        s.items.forEachFacet(def.key) { id, keys -> byItem[id] = keys }
        if (def.infer) s.suggestions.ofFacets(listOf(def.key)).forEach { (id, chances) ->
            byItem[id] = ModelValues.of(s.schema, mapOf(def.key to byItem[id].orEmpty()), chances)[def.key].orEmpty()
        }
        corrections.forEach { (id, list) ->
            val own = list.filter { it.facet == def.key }
            if (own.isNotEmpty()) byItem[id] = Corrected.facets(mapOf(def.key to byItem[id].orEmpty()), own)[def.key].orEmpty()
        }
        return byItem
    }

    /** The filter facets of a content type: their values with how many works have each, and the works with none. */
    fun facets(typeId: String): List<FacetFilter> {
        val type = stores.type(typeId) ?: return emptyList()
        class Group(val labelKey: String, val noneLabelKey: String?) {
            val uses = HashMap<String, Int>()
            val names = HashMap<String, String>()
            val nameKeys = HashMap<String, String>()
            var with = 0
        }
        val groups = LinkedHashMap<String, Group>()
        var total = 0
        type.sources.forEach { s ->
            total += s.items.count()
            val corrections = s.corrections.allFacets().groupBy { it.itemId }
            s.schema.facets.filter { it.filter }.forEach { def ->
                val group = groups.getOrPut(filterId(s, def)) {
                    Group(I18nKeys.facetLabel(s.id, def), if (def.none) I18nKeys.facetNoneLabel(s.id, def) else null)
                }
                valuesOf(s, def, corrections).values.forEach { keys ->
                    if (keys.isNotEmpty()) group.with++
                    keys.forEach { group.uses.merge(it, 1, Int::plus) }
                }
                group.names += s.names(def.key, group.uses.keys)
                group.uses.keys.forEach { key -> s.nameKey(def.key, key)?.let { group.nameKeys.putIfAbsent(key, it) } }
                corrections.values.flatten().filter { it.facet == def.key && it.name != null }.forEach { group.names.putIfAbsent(it.key, it.name!!) }
            }
        }
        return groups.map { (id, g) ->
            FacetFilter(
                id = id,
                labelKey = g.labelKey,
                noneLabelKey = g.noneLabelKey,
                itemsWithout = total - g.with,
                propertyValues = g.uses.entries.sortedByDescending { it.value }.map { (key, n) -> FacetValueUse(key, g.names[key] ?: key, n, g.nameKeys[key]) },
            )
        }
    }
}
