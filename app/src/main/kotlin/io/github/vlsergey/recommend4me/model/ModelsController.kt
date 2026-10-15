package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.ModelsApi
import io.github.vlsergey.recommend4me.api.model.MethodChoice
import io.github.vlsergey.recommend4me.api.model.MethodQuality
import io.github.vlsergey.recommend4me.api.model.ModelQuality
import io.github.vlsergey.recommend4me.api.model.FacetComparison as ApiFacetComparison
import io.github.vlsergey.recommend4me.api.model.ModelState
import io.github.vlsergey.recommend4me.api.model.ModelTrial
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetComparison
import io.github.vlsergey.recommend4me.suggestion.FacetComparisons
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.SuggesterStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset

/**
 * THE MODELS OF A CONTENT TYPE, for the page of the models: the model of the grades, the model of
 * interest, and the model of every facet the application works out of every source — each with how
 * it was last fitted, read from what is kept of it.
 */
@RestController
class ModelsController(
    private val recommendations: Recommendations,
    private val suggestions: FacetSuggestions,
    private val comparisons: FacetComparisons,
    private val plugins: Plugins,
    private val stores: Stores,
) : ModelsApi {

    override fun listModels(type: String): ResponseEntity<List<ModelState>> {
        val typeStore = stores.type(type) ?: return ResponseEntity.notFound().build()
        val scorers = plugins.scorers.map { it.id }
        val summary = recommendations.summary(type)
        val grade = summary.meta.let { meta ->
            ModelState(
                id = GRADE, kind = ModelState.Kind.GRADE, method = summary.scorer, methods = scorers,
                fittedAt = meta?.trainedAtMillis?.let(::time), millis = meta?.trainMillis,
                quality = meta?.metrics?.let { ModelQuality(ModelQuality.Measure.CONCORDANCE, it.concordance, it.concordanceSe) },
                settings = meta?.let { mapOf(PARAMETER to it.parameter.toString()) }.orEmpty(),
                items = meta?.ratingCounts?.sum(),
                tried = meta?.candidates.orEmpty().map(::trial),
                running = summary.training, stale = summary.stale,
            )
        }
        val interest = recommendations.interestMeta(type).let { meta ->
            ModelState(
                id = INTEREST, kind = ModelState.Kind.INTEREST, method = plugins.interestScorer(type)?.id, methods = scorers,
                fittedAt = meta?.trainedAtMillis?.let(::time), millis = meta?.trainMillis,
                quality = meta?.metrics?.let { ModelQuality(ModelQuality.Measure.AUC, it.concordance, it.concordanceSe) },
                settings = meta?.let { mapOf(PARAMETER to it.parameter.toString()) }.orEmpty(),
                items = meta?.let { it.interesting + it.notInteresting }, positives = meta?.interesting,
                tried = meta?.candidates.orEmpty().map(::trial),
                running = summary.training,
            )
        }
        val methods = plugins.suggesters.map { it.id }
        val facets = typeStore.sources.flatMap { store ->
            val kept = store.suggestions.suggesters().associateBy(SuggesterStatus::facet)
            suggestions.facetsOf(store).map { def ->
                val s = kept[def.key]
                val report = s?.fit?.report
                ModelState(
                    id = "facet:${store.id}:${def.key}", kind = ModelState.Kind.FACET, source = store.id, facet = def.key,
                    labelKey = I18nKeys.facetLabel(store.id, def),
                    method = plugins.suggester(store.id, def.key)?.id, methods = methods,
                    fittedAt = s?.fittedAt?.atOffset(ZoneOffset.UTC), millis = s?.fit?.millis,
                    quality = report?.let { ModelQuality(ModelQuality.Measure.LOSS, it.loss, it.lossError) },
                    settings = report?.chosen.orEmpty(),
                    items = s?.fit?.items, propertyValues = s?.fit?.values,
                    tried = report?.tried.orEmpty().map { ModelTrial(it.settings, it.loss, it.lossError, it.millis) },
                    fromScratch = report?.fromScratch,
                    running = suggestions.searching(store, def.key),
                )
            }
        }
        return ResponseEntity.ok(listOf(grade, interest) + facets)
    }

    override fun getFacetComparison(type: String, source: String, facet: String): ResponseEntity<ApiFacetComparison> {
        val store = stores.type(type)?.sources?.firstOrNull { it.id == source } ?: return ResponseEntity.notFound().build()
        val comparison = comparisons.of(store, facet) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(comparison.toApi())
    }

    override fun startFacetComparison(type: String, source: String, facet: String): ResponseEntity<ApiFacetComparison> {
        val store = stores.type(type)?.sources?.firstOrNull { it.id == source } ?: return ResponseEntity.notFound().build()
        if (suggestions.facetsOf(store).none { it.key == facet }) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(comparisons.start(store, facet).toApi())
    }

    override fun chooseFacetMethod(type: String, source: String, facet: String, methodChoice: MethodChoice): ResponseEntity<Unit> {
        val store = stores.type(type)?.sources?.firstOrNull { it.id == source } ?: return ResponseEntity.notFound().build()
        return if (suggestions.chooseMethod(store, facet, methodChoice.method)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    private fun FacetComparison.toApi() = ApiFacetComparison(
        startedAt = startedAt.atOffset(ZoneOffset.UTC), finishedAt = finishedAt?.atOffset(ZoneOffset.UTC), items = items,
        methods = methods.map { m ->
            MethodQuality(
                method = m.method, state = MethodQuality.State.valueOf(m.state.name), error = m.error, millis = m.millis,
                quality = m.report?.let { ModelQuality(ModelQuality.Measure.LOSS, it.loss, it.lossError) },
                settings = m.report?.chosen.orEmpty(),
            )
        },
    )

    override fun searchFacetModel(type: String, source: String, facet: String): ResponseEntity<Unit> {
        val store = stores.type(type)?.sources?.firstOrNull { it.id == source } ?: return ResponseEntity.notFound().build()
        return if (suggestions.searchAnew(store, facet)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    private fun trial(r: RankingResult) = ModelTrial(mapOf(PARAMETER to r.parameter.toString()), r.metrics.concordance, r.metrics.concordanceSe)

    private fun time(millis: Long) = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)

    private companion object {
        const val GRADE = "grade"
        const val INTEREST = "interest"

        /** The name the scorer's one parameter is shown by. */
        const val PARAMETER = "parameter"
    }
}
