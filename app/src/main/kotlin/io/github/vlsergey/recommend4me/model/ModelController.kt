package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.ModelApi
import io.github.vlsergey.recommend4me.api.model.GradeOnScale
import io.github.vlsergey.recommend4me.api.model.ModelCandidate
import io.github.vlsergey.recommend4me.api.model.ModelInfo
import io.github.vlsergey.recommend4me.api.model.ModelMetrics
import io.github.vlsergey.recommend4me.api.model.ScorerChoice
import io.github.vlsergey.recommend4me.api.model.ScorerInfo
import io.github.vlsergey.recommend4me.api.model.ModelWorth as ApiModelWorth
import io.github.vlsergey.recommend4me.api.model.PartWorth as ApiPartWorth
import io.github.vlsergey.recommend4me.api.model.WeighedGroup as ApiWeighedGroup
import io.github.vlsergey.recommend4me.api.model.Weighing as ApiWeighing
import io.github.vlsergey.recommend4me.api.model.WeighingFold as ApiWeighingFold
import io.github.vlsergey.recommend4me.api.model.ScorerComparison as ApiScorerComparison
import io.github.vlsergey.recommend4me.api.model.ScorerQuality as ApiScorerQuality
import io.github.vlsergey.recommend4me.item.toApi
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset

@RestController
class ModelController(
    private val recommendations: Recommendations,
    private val comparisons: ScorerComparisons,
    private val measurings: ModelMeasurings,
    private val plugins: Plugins,
    private val stores: Stores,
) : ModelApi {

    override fun getModel(type: String): ResponseEntity<ModelInfo> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(recommendations.summary(type).toApi(type))
    }

    override fun trainModel(type: String): ResponseEntity<ModelInfo> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        recommendations.trainSoon(type)
        return ResponseEntity.ok(recommendations.summary(type).toApi(type))
    }

    override fun chooseScorer(type: String, scorerChoice: ScorerChoice): ResponseEntity<ModelInfo> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        if (!recommendations.chooseScorer(type, scorerChoice.scorer)) {
            throw Refusal.NO_SCORER.refuse(HttpStatus.BAD_REQUEST, "There is no scorer '${scorerChoice.scorer}'", "scorer" to scorerChoice.scorer)
        }
        return ResponseEntity.ok(recommendations.summary(type).toApi(type))
    }

    override fun getScorerComparison(type: String, interest: Boolean): ResponseEntity<ApiScorerComparison> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        val comparison = comparisons.of(type, interest) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(comparison.toApi())
    }

    override fun startScorerComparison(type: String, interest: Boolean): ResponseEntity<ApiScorerComparison> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(comparisons.start(type, interest).toApi())
    }

    override fun chooseInterestScorer(type: String, scorerChoice: ScorerChoice): ResponseEntity<Unit> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        if (!recommendations.chooseInterestScorer(type, scorerChoice.scorer)) {
            throw Refusal.NO_SCORER.refuse(HttpStatus.BAD_REQUEST, "There is no scorer '${scorerChoice.scorer}'", "scorer" to scorerChoice.scorer)
        }
        return ResponseEntity.noContent().build()
    }

    private fun ScorerComparison.toApi() = ApiScorerComparison(
        startedAt = startedAt.atOffset(ZoneOffset.UTC),
        grades = grades,
        scorers = scorers.map { s ->
            ApiScorerQuality(
                scorer = s.scorer, state = ApiScorerQuality.State.valueOf(s.state.name),
                candidates = s.candidates.map { it.toApi() }, error = s.error, millis = s.millis, chosen = s.chosen?.toApi(),
            )
        },
        finishedAt = finishedAt?.atOffset(ZoneOffset.UTC),
    )

    override fun measurePartWorth(type: String): ResponseEntity<Unit> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        measurings.measureWorth(type)
        return ResponseEntity.accepted().build()
    }

    override fun getPartWorth(type: String): ResponseEntity<ApiModelWorth> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        val measuring = measurings.worthOf(type) ?: return ResponseEntity.notFound().build()
        if (measuring.finishedAt == null) return ResponseEntity.accepted().build()
        val worth = measuring.result ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            ApiModelWorth(
                scorer = worth.scorer,
                grades = worth.grades,
                parts = worth.parts.map { ApiPartWorth(it.part, it.labelKey, it.features, it.metrics?.toApi()) },
                full = worth.full?.toApi(),
            ),
        )
    }

    override fun measureWeighing(type: String, cautious: Boolean): ResponseEntity<Unit> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        measurings.measureWeighing(type, cautious)
        return ResponseEntity.accepted().build()
    }

    override fun getWeighing(type: String): ResponseEntity<ApiWeighing> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        val measuring = measurings.weighingOf(type) ?: return ResponseEntity.notFound().build()
        if (measuring.finishedAt == null) return ResponseEntity.accepted().build()
        val w = measuring.result ?: return ResponseEntity.notFound().build()
        val (baseline, baselineSe) = GroupWeights.meanAndError(w.folds.map { it.baseline })
        val (weighed, weighedSe) = GroupWeights.meanAndError(w.folds.map { it.weighed })
        val (gain, gainSe) = GroupWeights.meanAndError(w.folds.map { it.weighed - it.baseline })
        return ResponseEntity.ok(
            ApiWeighing(
                scorer = w.scorer,
                grades = w.grades,
                groups = w.groups.map { ApiWeighedGroup(it.part, it.labelKey, it.columns, it.weight) },
                folds = w.folds.map { f -> ApiWeighingFold(f.baseline, f.weighed, f.weights.toList()) },
                baseline = baseline, baselineSe = baselineSe, weighed = weighed, weighedSe = weighedSe, gain = gain, gainSe = gainSe,
                onAll = w.onAll?.toApi(),
            ),
        )
    }

    private fun RankingResult.toApi() = ModelCandidate(parameter, metrics.toApi())

    private fun RankingMetrics.toApi() = ModelMetrics(folds = folds, concordance = concordance, concordanceSe = concordanceSe, spearman = spearman, auc = auc)

    private fun ModelSummary.toApi(typeId: String): ModelInfo {
        val meta = meta
        // What the strongest features are shown by: their facets' and values' names as they are now
        val top = meta?.topPositive.orEmpty() + meta?.topNegative.orEmpty()
        val labels = if (top.isEmpty()) emptyMap() else FeatureNames.labels(top.map { it.feature }, stores.type(typeId)!!.sources)
        fun Contribution.toApi() = LabelledContribution(this, labels.getValue(feature)).toApi()
        return ModelInfo(
            trained = meta != null,
            training = training,
            stale = stale,
            textEncoderReady = textEncoderReady,
            imageEncoderReady = imageEncoderReady,
            scorer = scorer ?: "",
            scorers = plugins.scorers.map { ScorerInfo(it.id) },
            trainedAt = meta?.let { Instant.ofEpochMilli(it.trainedAtMillis).atOffset(ZoneOffset.UTC) },
            parameter = meta?.parameter,
            ladder = meta?.scale()?.grades().orEmpty().toSortedMap().map { (grade, score) ->
                GradeOnScale(grade, score, meta!!.ratingCounts[grade - Grades.MIN])
            },
            ratingCounts = ratingCounts.toList(),
            features = meta?.layout()?.width ?: 0,
            metrics = meta?.metrics?.toApi(),
            candidates = meta?.candidates.orEmpty().map { it.toApi() },
            topPositive = meta?.topPositive.orEmpty().map { it.toApi() },
            topNegative = meta?.topNegative.orEmpty().map { it.toApi() },
        )
    }
}
