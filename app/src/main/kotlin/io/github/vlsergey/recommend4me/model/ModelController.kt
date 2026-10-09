package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.ModelApi
import io.github.vlsergey.recommend4me.api.model.FeatureContribution
import io.github.vlsergey.recommend4me.api.model.GradeOnScale
import io.github.vlsergey.recommend4me.api.model.ModelCandidate
import io.github.vlsergey.recommend4me.api.model.ModelInfo
import io.github.vlsergey.recommend4me.api.model.ModelMetrics
import io.github.vlsergey.recommend4me.api.model.ScorerChoice
import io.github.vlsergey.recommend4me.api.model.ScorerInfo
import io.github.vlsergey.recommend4me.api.model.ModelWorth as ApiModelWorth
import io.github.vlsergey.recommend4me.api.model.PartWorth as ApiPartWorth
import io.github.vlsergey.recommend4me.api.model.ScorerComparison as ApiScorerComparison
import io.github.vlsergey.recommend4me.api.model.ScorerQuality as ApiScorerQuality
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneOffset

@RestController
class ModelController(
    private val recommendations: Recommendations,
    private val comparisons: ScorerComparisons,
    private val plugins: Plugins,
    private val stores: Stores,
) : ModelApi {

    override fun getModel(type: String): ResponseEntity<ModelInfo> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(recommendations.summary(type).toApi())
    }

    override fun trainModel(type: String): ResponseEntity<ModelInfo> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(recommendations.retrainAndScore(type).toApi())
    }

    override fun chooseScorer(type: String, scorerChoice: ScorerChoice): ResponseEntity<ModelInfo> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        val summary = recommendations.chooseScorer(type, scorerChoice.scorer) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Нет способа оценки «${scorerChoice.scorer}»")
        return ResponseEntity.ok(summary.toApi())
    }

    override fun getScorerComparison(type: String): ResponseEntity<ApiScorerComparison> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        val comparison = comparisons.of(type) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(comparison.toApi())
    }

    override fun startScorerComparison(type: String): ResponseEntity<ApiScorerComparison> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(comparisons.start(type).toApi())
    }

    private fun ScorerComparison.toApi() = ApiScorerComparison(
        startedAt = startedAt.atOffset(ZoneOffset.UTC),
        grades = grades,
        scorers = scorers.map { s ->
            ApiScorerQuality(
                scorer = s.scorer, title = s.title, state = ApiScorerQuality.State.valueOf(s.state.name),
                candidates = s.candidates.map { it.toApi() }, error = s.error, millis = s.millis, chosen = s.chosen?.toApi(),
            )
        },
        finishedAt = finishedAt?.atOffset(ZoneOffset.UTC),
    )

    override fun measurePartWorth(type: String): ResponseEntity<ApiModelWorth> {
        if (stores.type(type) == null) return ResponseEntity.notFound().build()
        val worth = recommendations.worth(type) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            ApiModelWorth(
                scorer = worth.scorer,
                grades = worth.grades,
                parts = worth.parts.map { ApiPartWorth(it.part, it.label, it.features, it.metrics?.toApi()) },
                full = worth.full?.toApi(),
            ),
        )
    }

    private fun RankingResult.toApi() = ModelCandidate(parameter, metrics.toApi())

    private fun RankingMetrics.toApi() = ModelMetrics(folds = folds, concordance = concordance, concordanceSe = concordanceSe, spearman = spearman, auc = auc)

    private fun Contribution.toApi() = FeatureContribution(feature = feature, label = label, contribution = contribution, present = present)

    private fun ModelSummary.toApi(): ModelInfo {
        val meta = meta
        return ModelInfo(
            trained = meta != null,
            training = training,
            stale = stale,
            textEncoderReady = textEncoderReady,
            imageEncoderReady = imageEncoderReady,
            scorer = scorer ?: "",
            scorers = plugins.scorers.map { ScorerInfo(it.id, it.title) },
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
