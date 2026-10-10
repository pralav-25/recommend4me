package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.FacetValueUse
import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
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
enum class ItemOrder { SCORE, UPDATED, NUMBER, SURPRISE }

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

    /** One row of the list before its card is read; [number] is what the list is sorted by when not by the prediction. */
    private class Row(val key: ItemKey, val updatedAt: Instant, val graded: Boolean, val prediction: Double?, val number: Double?)

    fun page(
        typeId: String,
        view: ItemView,
        order: ItemOrder,
        sortNumber: String?,
        line: String?,
        hidden: Set<String>,
        hiddenWithout: Set<String>,
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
        val hiddenValues = hidden.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

        val rows = ArrayList<Row>()
        type.sources.filter { sources == null || it.id in sources }.forEach { s ->
            val keys = s.items.keys()
            val versions = keys.associate { it.id to it.version }
            val grades = cards.graded(s.ratings.all(), versions).mapNotNull { (id, g) -> g.grade?.let { id to it } }.toMap()
            val predictions = type.models.predictions(s.id)
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
            val passes = facetFilter(s, hiddenValues, hiddenWithout)
            // The ranges of the numbers the source has, with their values: a source without one is not limited by it
            val limits = ranges.mapNotNull { r -> numberOf(s, r.numberId)?.let { r to it } }
            keys.forEach { k ->
                val key = ItemKey(s.id, k.id)
                if (relevance != null && key !in relevance) return@forEach
                if (passes != null && !passes(k.id)) return@forEach
                // A work without the number is not known to be within the range: not shown
                if (limits.any { (r, values) -> values[k.id]?.let { it !in r } ?: true }) return@forEach
                rows += Row(key, k.updatedAt, k.id in grades, predictions[k.id], numbers[k.id] ?: surprises[k.id])
            }
        }
        // A work graded in any of its sources is graded
        val gradedWorks = rows.filter { it.graded }.map { clusters.work(it.key) }.toSet()
        val viewed = rows.filter { row ->
            val graded = row.graded || (clusters.linked(row.key) && clusters.work(row.key) in gradedWorks)
            when (view) {
                ItemView.UNRATED -> !graded
                ItemView.RATED -> graded
                ItemView.ALL -> true
            }
        }
        val sorted = when {
            relevance != null -> viewed.sortedBy { relevance.getValue(it.key) }
            order == ItemOrder.SCORE -> viewed.sortedWith(compareByDescending<Row> { it.prediction ?: Double.NEGATIVE_INFINITY }.thenByDescending { it.updatedAt })
            order == ItemOrder.NUMBER || order == ItemOrder.SURPRISE ->
                viewed.sortedWith(compareByDescending<Row> { it.number ?: Double.NEGATIVE_INFINITY }.thenByDescending { it.updatedAt })
            else -> viewed.sortedByDescending { it.updatedAt }
        }
        val pageKeys = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)).map { it.key }
        val matches = found?.let { search.matches(typeId, it, pageKeys) }.orEmpty()
        return ItemPage(total = sorted.size, items = cards.cards(type, pageKeys, matches, clusters))
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
     * Whether an item of the source shows by the filters: in every filtered facet, one of its values
     * not [hidden] — or, without any, its facet's "none" line not [hiddenWithout]. A value the site
     * adds later is not hidden: it shows. Null when no facet of the source is filtered.
     */
    private fun facetFilter(s: SourceStore, hidden: Map<String, Set<String>>, hiddenWithout: Set<String>): ((String) -> Boolean)? {
        val filtered = s.schema.facets.filter { it.filter && (filterId(s, it) in hidden || filterId(s, it) in hiddenWithout) }
        if (filtered.isEmpty()) return null
        val corrections = s.corrections.allFacets().groupBy { it.itemId }
        val values = filtered.associateWith { def -> valuesOf(s, def, corrections) }
        return { id ->
            values.all { (def, byItem) ->
                val keys = byItem[id].orEmpty()
                val id2 = filterId(s, def)
                if (keys.isEmpty()) id2 !in hiddenWithout else keys.any { it !in hidden[id2].orEmpty() }
            }
        }
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
        class Group(val label: String, val noneLabel: String?) {
            val uses = HashMap<String, Int>()
            val names = HashMap<String, String>()
            var with = 0
        }
        val groups = LinkedHashMap<String, Group>()
        var total = 0
        type.sources.forEach { s ->
            total += s.items.count()
            val corrections = s.corrections.allFacets().groupBy { it.itemId }
            s.schema.facets.filter { it.filter }.forEach { def ->
                val group = groups.getOrPut(filterId(s, def)) { Group(def.label, def.noneLabel) }
                valuesOf(s, def, corrections).values.forEach { keys ->
                    if (keys.isNotEmpty()) group.with++
                    keys.forEach { group.uses.merge(it, 1, Int::plus) }
                }
                group.names += s.items.facetNames(def.key, group.uses.keys)
                corrections.values.flatten().filter { it.facet == def.key && it.name != null }.forEach { group.names.putIfAbsent(it.key, it.name!!) }
            }
        }
        return groups.map { (id, g) ->
            FacetFilter(
                id = id,
                label = g.label,
                noneLabel = g.noneLabel,
                itemsWithout = total - g.with,
                propertyValues = g.uses.entries.sortedByDescending { it.value }.map { (key, n) -> FacetValueUse(key, g.names[key] ?: key, n) },
            )
        }
    }
}
