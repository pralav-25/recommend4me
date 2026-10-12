package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.scorer.RankingTask

/**
 * What a feature adds to a work's score, in points of 0..10; for a categorical feature against the
 * average work of the catalogue — so a liked tag the work does NOT have ([present] false) shows as
 * what its absence costs.
 */
data class Contribution(val feature: String, val label: String, val contribution: Double, val present: Boolean = true)

/** The out-of-fold quality of the ranking without one part of the input ([part]: as in [ContributionGroup]), of [features] columns. */
class PartWorth(val part: String, val label: String, val features: Int, val metrics: RankingMetrics?)

/** The out-of-fold quality of the ranking on everything, and without every part in turn — the most needed part first. */
class ModelWorth(val scorer: String, val full: RankingMetrics?, val grades: Int, val parts: List<PartWorth>)

/** What a part of a work's input moves its score by as a whole — a facet, the pictures, a text — and every feature in it. */
data class ContributionGroup(val part: String, val label: String, val contribution: Double, val features: List<Contribution>)

/** Everything about a trained model but its scorer's state; kept as JSON beside it. */
data class ModelMeta(
    val scorer: String,
    /** The scorer's parameter, chosen by cross-validation. */
    val parameter: Double,
    val categorical: List<String>,
    /** Share of the catalogue's items with every [categorical] feature: the average work the explanation compares with. */
    val categoricalShare: FloatArray,
    /** Centre and spread of every vector block the model was trained with. */
    val vectorCenters: Map<String, FloatArray>,
    val vectorSpreads: Map<String, Float>,
    val numbers: Map<String, NumberSpread>,
    /** How the scorer's scores become 0..10: see [Scale]. */
    val scoreMean: Double,
    val scoreSd: Double,
    val ladder: Map<Int, Double>,
    val trainedAtMillis: Long,
    /** How many grades of every value, 1..5, the model was trained on. */
    val ratingCounts: IntArray,
    val metrics: RankingMetrics?,
    val candidates: List<RankingResult>,
    val topPositive: List<Contribution>,
    val topNegative: List<Contribution>,
    val textEncoder: String?,
    val imageEncoder: String?,
) {
    fun scale() = Scale(scoreMean, scoreSd, ladder)

    fun layout() = FeatureLayout(
        categorical,
        vectorCenters.mapValues { (key, center) -> VectorSpread(center, vectorSpreads.getValue(key)) },
        numbers,
    )
}

/**
 * The model of interest — whether the user finds a work interesting at all — but its scorer's
 * state; kept as JSON beside it. Its scores are standardised as on its training rows and made a
 * chance by the [calibration].
 */
data class InterestMeta(
    val scorer: String,
    val parameter: Double,
    val categorical: List<String>,
    val vectorCenters: Map<String, FloatArray>,
    val vectorSpreads: Map<String, Float>,
    val numbers: Map<String, NumberSpread>,
    val scoreMean: Double,
    val scoreSd: Double,
    val calibration: Calibration,
    val trainedAtMillis: Long,
    /** How many works it learnt as interesting, and as not. */
    val interesting: Int,
    val notInteresting: Int,
    /** Out-of-fold; the concordance of two kinds is the ROC AUC. */
    val metrics: RankingMetrics?,
) {
    fun layout() = FeatureLayout(
        categorical,
        vectorCenters.mapValues { (key, center) -> VectorSpread(center, vectorSpreads.getValue(key)) },
        numbers,
    )

    fun chance(raw: Double): Double = calibration.chance((raw - scoreMean) / scoreSd)
}

/** What the interface shows about the model of a content type. */
data class ModelSummary(
    val meta: ModelMeta?,
    val training: Boolean,
    val stale: Boolean,
    val textEncoderReady: Boolean,
    val imageEncoderReady: Boolean,
    val scorer: String?,
    /** How many grades of every value, 1..5, there are now. */
    val ratingCounts: IntArray,
)

/** A scorer's quality on the current grades. */
/** The rows a scorer is cross-validated on, as a fold sees them ([xFor]), and how many grades they are. */
class MeasuringInput(val task: RankingTask, val xFor: ((Set<Long>) -> Matrix)?, val grades: Int)
