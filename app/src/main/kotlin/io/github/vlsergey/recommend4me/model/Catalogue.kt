package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.interest.InterestMark
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.ln1p
import kotlin.math.max

private val log = LoggerFactory.getLogger(CatalogueReader::class.java)

/** What the model reads of an item beside its vectors: no texts, no addresses. */
class CatalogueItem(
    val key: ItemKey,
    val version: String,
    val updatedAt: Instant,
    /** Every categorical feature but the grade of an earlier version, which depends on the grades. */
    val categorical: Set<String>,
    val numeric: Map<String, Float>,
)

/** A grade of an item of a content type. */
class TypedRating(val key: ItemKey, val version: String, val grade: Int, val ratedAt: Instant)

/**
 * EVERYTHING THE MODEL READS OF EVERY ITEM OF A CONTENT TYPE, loaded in batch for one operation —
 * one pass over each source of each file: the items with their corrected facets and numbers, the
 * texts', pictures' and sets' vectors, the grades, the works — and handed to every step that needs
 * it. Nothing of it outlives the operation. Not the user's signals on the sites: they are the
 * user's verdict, the very thing the grades teach.
 */
class Catalogue(
    val type: TypeStore,
    val items: List<CatalogueItem>,
    val ratings: List<TypedRating>,
    /** Every block but the likeness to the marks, by item. */
    val vectors: Map<ItemKey, Map<String, FloatArray>>,
    val likeness: TypeLikeness,
    val works: WorkClusters,
    /** The user's marks of interest. */
    val interest: Map<ItemKey, InterestMark> = emptyMap(),
) {
    val byKey: Map<ItemKey, CatalogueItem> = items.associateBy { it.key }

    fun versionOf(key: ItemKey): String? = byKey[key]?.version

    fun vectorsOf(key: ItemKey): Map<String, FloatArray> = (vectors[key] ?: emptyMap()) + likeness.vectors(key)
}

/** Reads a [Catalogue], and the same of single items for the requests of the interface. */
@Component
class CatalogueReader(private val plugins: Plugins, private val works: Works, private val likenesses: Likenesses) {

    fun read(type: TypeStore, withLikeness: Boolean = true): Catalogue {
        val started = System.currentTimeMillis()
        val textEncoder = plugins.textEncoder()
        val imageEncoder = plugins.imageEncoder()
        val embeddings = type.embeddings.ids()
        val severalSources = type.sources.size > 1
        val items = ArrayList<CatalogueItem>()
        val vectors = HashMap<ItemKey, HashMap<String, FloatArray>>()
        val ratings = ArrayList<TypedRating>()
        val interest = HashMap<ItemKey, InterestMark>()
        val centerSum = imageEncoder?.let { DoubleArray(it.dim) }
        var pictures = 0

        type.sources.forEach { s ->
            items += readItems(s, severalSources)
            fun put(id: String, block: String, v: FloatArray) {
                vectors.getOrPut(ItemKey(s.id, id)) { HashMap() }[block] = v
            }
            if (textEncoder != null) {
                // The title and the site's lines too: every text that has vectors
                val blocks = TextVectors.blocks(s.schema).mapValues { it.value.first }
                s.textVectors.forEach(textEncoder.id, blocks.keys) { id, key, v -> put(id, blocks.getValue(key), v) }
            }
            if (imageEncoder != null) {
                s.pictures.forEachItemVector(imageEncoder.id) { id, block, v ->
                    put(id, block, v)
                    if (v.size == centerSum!!.size) {
                        for (k in v.indices) centerSum[k] += v[k].toDouble()
                        pictures++
                    }
                }
            }
            s.setVectors.forEach(embeddings) { id, key, v -> put(id, key, v) }
            ratings += s.ratings.all().map { TypedRating(ItemKey(s.id, it.itemId), it.version, it.grade, it.ratedAt) }
            s.interest.all().forEach { (id, mark) -> interest[ItemKey(s.id, id)] = mark }
        }
        val read = System.currentTimeMillis()
        val keys = items.map { it.key }.toSet()
        val graded = ratings.map { it.key }.filter { it in keys }.toSet()
        val center = centerSum?.takeIf { pictures > 0 }?.let { sum -> FloatArray(sum.size) { (sum[it] / pictures).toFloat() } }
        val clusters = works.of(type)
        // The latest grade of every graded item: what the likeness to the graded works reads
        val grades = ratings.filter { it.key in keys }.groupBy { it.key }.mapValues { (_, r) -> r.maxBy { it.ratedAt }.grade }
        val likeness = if (withLikeness) likenesses.forTraining(type, center, graded, grades, clusters::work) else likenesses.stored(type)
        log.info(
            "{}: catalogue of {} items read in {} ms, likeness in {} ms",
            type.id, items.size, read - started, System.currentTimeMillis() - read,
        )
        return Catalogue(type, items, ratings.filter { it.key in keys }, vectors, likeness, clusters, interest)
    }

    /**
     * The items of one source as the model reads them: their facets and numbers with the user's
     * corrections, the source when the type has several.
     */
    private fun readItems(s: SourceStore, severalSources: Boolean): List<CatalogueItem> {
        val facetIds = s.schema.facets.filter { it.feature }.associate { it.key to FeatureNames.facetId(s.source, it) }
        val facets = HashMap<String, HashMap<String, MutableList<String>>>()
        s.items.forEachFacetValue { id, facet, key ->
            if (facet in facetIds) facets.getOrPut(id) { HashMap() }.getOrPut(facet) { ArrayList() } += key
        }
        val facetCorrections = s.corrections.allFacets().groupBy { it.itemId }
        // The values the model gives the items of the facets it works out
        val chances = s.suggestions.ofFacets(s.schema.facets.filter { it.infer && it.feature }.map { it.key })
        val numberDefs = s.schema.numbers.filter { it.feature }.associateBy { it.key }
        val numbers = HashMap<String, HashMap<String, Double>>()
        s.items.forEachNumber { id, key, value -> if (key in numberDefs) numbers.getOrPut(id) { HashMap() }[key] = value }
        val fields = s.corrections.allFields()
        // A tag under another gives the work the other too; a tag the user said it has not, none under it
        val hierarchies = s.hierarchies()
        return s.items.keys().map { k ->
            val categorical = LinkedHashSet<String>()
            val base = ModelValues.of(s.schema, facets[k.id].orEmpty(), chances[k.id])
            TagHierarchy.corrected(base, facetCorrections[k.id].orEmpty(), hierarchies).forEach { (facet, keys) ->
                val facetId = facetIds[facet] ?: return@forEach
                keys.forEach { categorical += FeatureNames.facet(facetId, it) }
            }
            if (severalSources) categorical += "source:${s.id}"
            val numeric = Corrected.numbers(numbers[k.id].orEmpty(), fields[k.id].orEmpty()).mapNotNull { (key, value) ->
                val def = numberDefs[key] ?: return@mapNotNull null
                FeatureNames.number(FeatureNames.numberId(s.source, def)) to transform(def.scale, value)
            }.toMap()
            CatalogueItem(ItemKey(s.id, k.id), k.version, k.updatedAt, categorical, numeric)
        }
    }

    /** One item as the model reads it, without its vectors; null when there is no such item. */
    fun item(type: TypeStore, key: ItemKey): CatalogueItem? {
        val s = type.source(key.source) ?: return null
        val head = s.items.find(key.id) ?: return null
        val facetIds = s.schema.facets.filter { it.feature }.associate { it.key to FeatureNames.facetId(s.source, it) }
        val categorical = LinkedHashSet<String>()
        val base = ModelValues.of(s.schema, s.items.facets(key.id), s.suggestions.ofItems(listOf(key.id))[key.id])
        TagHierarchy.corrected(base, s.corrections.facetsOf(key.id), s.hierarchies()).forEach { (facet, keys) ->
            val facetId = facetIds[facet] ?: return@forEach
            keys.forEach { categorical += FeatureNames.facet(facetId, it) }
        }
        if (type.sources.size > 1) categorical += "source:${s.id}"
        val numberDefs = s.schema.numbers.filter { it.feature }.associateBy { it.key }
        val numeric = Corrected.numbers(s.items.numbers(key.id), s.corrections.fieldsOf(key.id)).mapNotNull { (k, value) ->
            val def = numberDefs[k] ?: return@mapNotNull null
            FeatureNames.number(FeatureNames.numberId(s.source, def)) to transform(def.scale, value)
        }.toMap()
        return CatalogueItem(key, head.version, head.updatedAt, categorical, numeric)
    }

    /** The vectors of one item — texts, pictures, sets — without its likeness to the marks. */
    fun vectors(type: TypeStore, key: ItemKey): Map<String, FloatArray> {
        val s = type.source(key.source) ?: return emptyMap()
        val out = HashMap<String, FloatArray>()
        plugins.textEncoder()?.let { encoder ->
            val blocks = TextVectors.blocks(s.schema).mapValues { it.value.first }
            s.textVectors.ofMany(listOf(key.id), encoder.id)[key.id]?.forEach { (k, v) -> blocks[k]?.let { out[it] = v } }
        }
        plugins.imageEncoder()?.let { encoder -> s.pictures.itemVectorsOf(listOf(key.id), encoder.id)[key.id]?.let(out::putAll) }
        s.setVectors.ofMany(listOf(key.id), type.embeddings.ids())[key.id]?.let(out::putAll)
        return out
    }

    companion object {
        fun transform(scale: NumberScale, value: Double): Float = when (scale) {
            NumberScale.LOG -> ln1p(max(value, 0.0)).toFloat()
            NumberScale.LINEAR -> value.toFloat()
        }

        /** For each rating, the grade of an earlier, different version of the same item. */
        fun previousGradesOfRatings(all: List<TypedRating>): Map<TypedRating, Int> {
            val result = HashMap<TypedRating, Int>()
            all.groupBy { it.key }.values.forEach { list ->
                val sorted = list.sortedBy { it.ratedAt }
                sorted.forEachIndexed { i, rating ->
                    sorted.subList(0, i).lastOrNull { it.version != rating.version }?.let { result[rating] = it.grade }
                }
            }
            return result
        }

        /** For each item, the latest grade of a version other than the current one. */
        fun previousGradesOfCurrentVersions(all: List<TypedRating>, versionOf: (ItemKey) -> String?): Map<ItemKey, Int> =
            all.groupBy { it.key }.mapNotNull { (key, list) ->
                val version = versionOf(key) ?: return@mapNotNull null
                list.filter { it.version != version }.maxByOrNull { it.ratedAt }?.let { key to it.grade }
            }.toMap()

        fun inputOf(item: CatalogueItem, vectors: Map<String, FloatArray>, previousGrade: Int?): ItemInput =
            ItemInput(item.key, vectors, if (previousGrade == null) item.categorical else item.categorical + "prev:$previousGrade", item.numeric)

        fun rating(s: SourceStore, r: Rating) = TypedRating(ItemKey(s.id, r.itemId), r.version, r.grade, r.ratedAt)
    }
}
