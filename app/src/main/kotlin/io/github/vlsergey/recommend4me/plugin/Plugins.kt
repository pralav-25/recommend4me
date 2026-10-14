package io.github.vlsergey.recommend4me.plugin

import io.github.vlsergey.recommend4me.contenttype.ContentType
import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.encoder.TextReranker
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.scorer.Scorer
import io.github.vlsergey.recommend4me.search.SearchProvider
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.suggestion.FacetSuggester
import io.github.vlsergey.recommend4me.tag.TagCatalogue
import io.github.vlsergey.recommend4me.universe.UniverseCatalogue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Plugins::class.java)

/**
 * What the plugins brought: the sources, the encoders, the scorers, the search, the content types —
 * and which of several of a kind is in use.
 */
@Component
class Plugins(
    sources: ObjectProvider<Source>,
    textEncoders: ObjectProvider<TextEncoder>,
    imageEncoders: ObjectProvider<ImageEncoder>,
    rerankers: ObjectProvider<TextReranker>,
    scorers: ObjectProvider<Scorer>,
    searches: ObjectProvider<SearchProvider>,
    suggesters: ObjectProvider<FacetSuggester>,
    catalogues: ObjectProvider<UniverseCatalogue>,
    tagCatalogues: ObjectProvider<TagCatalogue>,
    types: ObjectProvider<ContentType>,
    private val settings: Settings,
) {
    val sources: List<Source> = sources.orderedStream().toList()
    val textEncoders: List<TextEncoder> = textEncoders.orderedStream().toList()
    val imageEncoders: List<ImageEncoder> = imageEncoders.orderedStream().toList()
    val rerankers: List<TextReranker> = rerankers.orderedStream().toList()
    val scorers: List<Scorer> = scorers.orderedStream().toList()
    val search: SearchProvider? = searches.orderedStream().toList().firstOrNull()
    val suggester: FacetSuggester? = suggesters.orderedStream().toList().firstOrNull()
    val catalogues: List<UniverseCatalogue> = catalogues.orderedStream().toList()

    /** The catalogues the tags are bound to entries of ([TagCatalogue]). */
    val tagCatalogues: List<TagCatalogue> = tagCatalogues.orderedStream().toList()
    private val allTypes = types.orderedStream().toList()

    init {
        for (s in this.sources) {
            require(allTypes.any { it.id == s.contentType }) { "The source ${s.id} is of an unknown content type ${s.contentType}" }
        }
        require(this.sources.map { it.id }.distinct().size == this.sources.size) { "Two sources of one id: ${this.sources.map { it.id }}" }
        log.info(
            "sources {}, text encoders {}, picture encoders {}, rerankers {}, scorers {}, search {}, suggester {}, catalogues {}, tag catalogues {}",
            this.sources.map { it.id }, this.textEncoders.map { it.id }, this.imageEncoders.map { it.id }, this.rerankers.map { it.id },
            this.scorers.map { it.id }, search?.id, suggester?.id, this.catalogues.map { it.id }, this.tagCatalogues.map { it.id },
        )
    }

    /** The content types that have a source. */
    val types: List<ContentType> get() = allTypes.filter { t -> sources.any { it.contentType == t.id } }

    fun type(id: String): ContentType? = types.firstOrNull { it.id == id }

    fun source(id: String): Source? = sources.firstOrNull { it.id == id }

    fun sourcesOf(typeId: String): List<Source> = sources.filter { it.contentType == typeId }

    /** The text encoder in use: the one named by the setting `encoder.text`, or the first that is ready. */
    fun textEncoder(): TextEncoder? = choose(textEncoders, "encoder.text", { it.id }, { it.ready() })

    fun imageEncoder(): ImageEncoder? = choose(imageEncoders, "encoder.image", { it.id }, { it.ready() })

    /** The reranker in use: the one named by the setting `encoder.reranker`, or the first that is ready. */
    fun reranker(): TextReranker? = choose(rerankers, "encoder.reranker", { it.id }, { it.ready() })

    /** The scorer a content type is ranked by: the one named by `scorer.<type>`, or the pairwise ranking, or the first. */
    fun scorer(typeId: String): Scorer? =
        scorers.firstOrNull { it.id == settings.get("scorer.$typeId") }
            ?: scorers.firstOrNull { it.id == DEFAULT_SCORER }
            ?: scorers.firstOrNull()

    private fun <T> choose(all: List<T>, setting: String, id: (T) -> String, ready: (T) -> Boolean): T? {
        val named = settings.get(setting)
        return all.firstOrNull { id(it) == named && ready(it) } ?: all.firstOrNull(ready)
    }

    companion object {
        const val DEFAULT_SCORER = "pairwise-logistic"
    }
}
