package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.picture.PictureRepository
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.likeness.MarkLikeness
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
     * What the user is shown of a feature, by keys of the interface's words: the label of a facet
     * with the value's name ("standard:facet.tag" and "стелс"), of a standard value by its key; the
     * label of a number, of a text, of a block of the input ("standard:model.cover"). [sources]
     * give the facets, numbers and texts; the names of facet values are read from their sources.
     */
    fun labels(features: Collection<String>, sources: List<SourceStore>): Map<String, FeatureLabel> {
        val facetLabels = HashMap<String, String>()
        val numberLabels = HashMap<String, String>()
        val blockLabels = HashMap<String, String>()
        sources.forEach { s ->
            s.schema.facets.forEach { facetLabels.putIfAbsent(facetId(s.source, it), I18nKeys.facetLabel(s.id, it)) }
            s.schema.numbers.forEach { numberLabels.putIfAbsent(numberId(s.source, it), I18nKeys.numberLabel(s.id, it)) }
            TextVectors.blocks(s.id, s.schema).values.forEach { (block, label) -> blockLabels.putIfAbsent(block, label) }
        }
        // The names of the facet values and the keys of those of the application's own, asked of every source that has the facet
        val wanted = features.mapNotNull(::parseFacet).groupBy({ it.first }, { it.second })
        val names = HashMap<Pair<String, String>, String>()
        val nameKeys = HashMap<Pair<String, String>, String>()
        wanted.forEach { (facetId, keys) ->
            sources.forEach { s ->
                s.schema.facets.filter { facetId(s.source, it) == facetId }.forEach { def ->
                    s.names(def.key, keys).forEach { (key, name) -> names.putIfAbsent(facetId to key, name) }
                    keys.forEach { key -> s.nameKey(def.key, key)?.let { nameKeys.putIfAbsent(facetId to key, it) } }
                    s.corrections.allFacets().filter { it.facet == def.key && it.name != null }.forEach { names.putIfAbsent(facetId to it.key, it.name!!) }
                }
            }
        }
        return features.associateWith { feature ->
            parseFacet(feature)?.let { (facetId, key) ->
                FeatureLabel(facetLabels[facetId] ?: facetId, names[facetId to key] ?: key, nameKeys[facetId to key])
            } ?: when (feature.substringBefore(':')) {
                "num" -> FeatureLabel(numberLabels[feature.removePrefix("num:")] ?: feature)
                "prev" -> FeatureLabel(PREVIOUS_LABEL, feature.removePrefix("prev:"))
                "source" -> feature.removePrefix("source:").let { id -> FeatureLabel(SOURCE_LABEL, id, I18nKeys.qualified(id, I18nKeys.TITLE)) }
                else -> FeatureLabel(BLOCKS[feature] ?: blockLabels[feature] ?: feature)
            }
        }
    }

    /** The parts an explanation switches off as a whole besides the facets and single blocks. */
    const val PREVIOUS = "part:previous"
    const val SOURCE = "part:source"
    const val PICTURES = "part:pictures"
    const val REVIEWS = "part:reviews"

    /**
     * The key of the label of a part of an explanation: the facet's label for "facet:<id>", the
     * model's word for the joined parts, the feature's own label for a single block.
     */
    fun partLabels(parts: Collection<String>, sources: List<SourceStore>): Map<String, String> {
        val facetLabels = HashMap<String, String>()
        sources.forEach { s -> s.schema.facets.forEach { facetLabels.putIfAbsent("facet:" + facetId(s.source, it), I18nKeys.facetLabel(s.id, it)) } }
        val single = labels(parts.filter { !it.startsWith("facet:") && !it.startsWith("part:") }, sources)
        return parts.associateWith { part ->
            facetLabels[part] ?: when (part) {
                PREVIOUS -> PREVIOUS_LABEL
                SOURCE -> SOURCE_LABEL
                PICTURES -> model("pictures")
                REVIEWS -> model("reviews")
                else -> single[part]?.labelKey ?: part
            }
        }
    }

    /** A word of the model's input in the standard namespace: "standard:model.cover". */
    private fun model(key: String) = I18nKeys.qualified(I18nKeys.STANDARD, "model.$key")

    private val PREVIOUS_LABEL = model("previous")
    private val SOURCE_LABEL = model("source")

    /** The keys of the labels of the blocks of the model's input that are not a source's texts. */
    private val BLOCKS = mapOf(
        PictureRepository.COVER to model("cover"),
        PictureRepository.SCREENS to model("screens"),
        SetKind.SCREENS.key to model("screensOneByOne"),
        SetKind.REVIEWS.key to model("reviewsOneByOne"),
        SetKind.PARTS.key to model("textFragments"),
        MarkLikeness.PICTURES to model("likePictures"),
        MarkLikeness.REVIEWS to model("likeReviews"),
    )

    /** Every key of the standard namespace a feature or a part of the model's input is labelled by. */
    val STANDARD_KEYS: Set<String> = setOf(PREVIOUS_LABEL, SOURCE_LABEL, model("pictures"), model("reviews"), TextVectors.TITLE_LABEL) + BLOCKS.values
}

/**
 * What the user is shown of a feature, by keys of the interface's words: the key of the label of
 * what it is of — a facet, a number, a block of the input, the previous grade, the source — and of
 * a feature of one value that value as the data names it ([value]), or the key of its name
 * ([valueKey]: a standard value, an original character, a source's title).
 */
data class FeatureLabel(val labelKey: String, val value: String? = null, val valueKey: String? = null)
