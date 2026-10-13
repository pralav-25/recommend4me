package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.picture.PictureRepository
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.likeness.MarkLikeness
import io.github.vlsergey.recommend4me.likeness.WorkLikeness
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * The names of the model's features, and what the user is shown of them.
 *
 *     facet:<facet id>:<value key>   a value of a facet; the facet id is the facet's shared key, or
 *                                    "<source>.<facet>" — facets of one shared key are one facet
 *     num:<number id>                a number, the same way
 *     prev:<grade>                  the grade of an earlier version of the same item
 *     source:<id>                    where the work comes from, when a type has several sources
 *     text:…, img:…, set:…, marks:…  vector blocks
 */
object FeatureNames {

    fun facetId(source: Source, facet: FacetDef): String = facet.shared ?: "${source.id}.${facet.key}"

    fun numberId(source: Source, number: NumberDef): String = number.shared ?: "${source.id}.${number.key}"

    fun facet(facetId: String, key: String) = "facet:$facetId:$key"

    fun number(numberId: String) = "num:$numberId"

    /** Splits "facet:<id>:<key>" into the facet id and the key; null for another feature. */
    fun parseFacet(feature: String): Pair<String, String>? {
        if (!feature.startsWith("facet:")) return null
        val rest = feature.removePrefix("facet:")
        val at = rest.indexOf(':')
        return if (at < 0) null else rest.substring(0, at) to rest.substring(at + 1)
    }

    fun isCategorical(feature: String) = feature.substringBefore(':') in setOf("facet", "prev", "source")

    /**
     * What the user is shown of a feature: "Тег: стелс", "Обложка". [sources] give the labels of
     * facets, numbers and texts; the names of facet values are read from their sources.
     */
    fun labels(features: Collection<String>, sources: List<SourceStore>): Map<String, String> {
        val facetLabels = HashMap<String, String>()
        val numberLabels = HashMap<String, String>()
        val blockLabels = HashMap<String, String>()
        sources.forEach { s ->
            s.schema.facets.forEach { facetLabels.putIfAbsent(facetId(s.source, it), it.label) }
            s.schema.numbers.forEach { numberLabels.putIfAbsent(numberId(s.source, it), it.label) }
            TextVectors.blocks(s.schema).values.forEach { (block, label) -> blockLabels.putIfAbsent(block, label) }
        }
        // The names of the facet values, asked of every source that has the facet
        val wanted = features.mapNotNull(::parseFacet).groupBy({ it.first }, { it.second })
        val names = HashMap<Pair<String, String>, String>()
        wanted.forEach { (facetId, keys) ->
            sources.forEach { s ->
                s.schema.facets.filter { facetId(s.source, it) == facetId }.forEach { def ->
                    s.names(def.key, keys).forEach { (key, name) -> names.putIfAbsent(facetId to key, name) }
                    s.corrections.allFacets().filter { it.facet == def.key && it.name != null }.forEach { names.putIfAbsent(facetId to it.key, it.name!!) }
                }
            }
        }
        return features.associateWith { feature ->
            parseFacet(feature)?.let { (facetId, key) -> "${facetLabels[facetId] ?: facetId}: ${names[facetId to key] ?: key}" }
                ?: when (val group = feature.substringBefore(':')) {
                    "num" -> numberLabels[feature.removePrefix("num:")] ?: feature
                    "prev" -> "Оценка прошлой версии: " + feature.removePrefix("prev:")
                    "source" -> "Источник: " + (sources.firstOrNull { it.id == feature.removePrefix("source:") }?.source?.title ?: feature)
                    else -> BLOCKS[feature] ?: blockLabels[feature] ?: group
                }
        }
    }

    /** The parts an explanation switches off as a whole besides the facets and single blocks. */
    const val PREVIOUS = "part:previous"
    const val SOURCE = "part:source"
    const val PICTURES = "part:pictures"
    const val REVIEWS = "part:reviews"

    /**
     * What the user is shown of a part of an explanation: the facet's label for "facet:<id>",
     * a name for the joined parts, the feature's own label for a single block.
     */
    fun partLabels(parts: Collection<String>, sources: List<SourceStore>): Map<String, String> {
        val facetLabels = HashMap<String, String>()
        sources.forEach { s -> s.schema.facets.forEach { facetLabels.putIfAbsent("facet:" + facetId(s.source, it), it.label) } }
        val single = labels(parts.filter { !it.startsWith("facet:") && !it.startsWith("part:") }, sources)
        return parts.associateWith { part ->
            facetLabels[part] ?: when (part) {
                PREVIOUS -> "Оценка прошлой версии"
                SOURCE -> "Источник"
                PICTURES -> "Обложка и картинки"
                REVIEWS -> "Отзывы"
                else -> single[part] ?: part
            }
        }
    }

    private val BLOCKS = mapOf(
        PictureRepository.COVER to "Обложка",
        PictureRepository.SCREENS to "Скриншоты в среднем",
        SetKind.SCREENS.key to "Скриншоты по одному",
        SetKind.REVIEWS.key to "Отзывы по одному",
        SetKind.PARTS.key to "Текст по фрагментам",
        MarkLikeness.PICTURES to "Похожесть на отмеченные картинки",
        MarkLikeness.REVIEWS to "Похожесть на отмеченные отзывы",
        WorkLikeness.WORKS to "Похожесть описания на оценённые работы",
    )
}
