package io.github.vlsergey.recommend4me.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * A SCORE MADE A CHANCE, by Platt's scaling: chance = σ([slope]·z + [intercept]), z a standardised
 * score. Fitted on the scores of works the model had not seen (the cross-validation's) — on its own
 * training works a model is surer than it will be on new ones.
 *
 * The targets are Platt's: not 1 and 0 but (N₊+1)/(N₊+2) and 1/(N₋+2), what Bayes' rule gives with
 * a uniform prior on every work's chance — so a few labels are never taken for certainty. Fitted by
 * Newton's method with a step halved until the loss falls (Lin, Lin and Weng, 2007).
 */
data class Calibration(val slope: Double, val intercept: Double) {

    fun chance(z: Double): Double = sigmoid(slope * z + intercept)

    companion object {
        private const val MAX_ITERATIONS = 100
        private const val MIN_STEP = 1e-10

        /** Keeps the Hessian invertible when the scores are all alike. */
        private const val RIDGE = 1e-12

        /** The gradient this small: the minimum is reached. */
        private const val TOLERANCE = 1e-5

        /** Null when one of the two kinds is missing: there is nothing to tell apart. */
        fun fit(z: DoubleArray, positive: BooleanArray): Calibration? {
            require(z.size == positive.size) { "${z.size} scores, ${positive.size} labels" }
            val pos = positive.count { it }
            val neg = positive.size - pos
            if (pos == 0 || neg == 0) return null
            val high = (pos + 1.0) / (pos + 2.0)
            val low = 1.0 / (neg + 2.0)
            val t = DoubleArray(z.size) { if (positive[it]) high else low }
            fun loss(a: Double, b: Double): Double = z.indices.sumOf { i ->
                val f = a * z[i] + b
                // −t·ln σ(f) − (1−t)·ln(1−σ(f)), without overflow either way
                t[i] * softplus(-f) + (1 - t[i]) * softplus(f)
            }
            var a = 0.0
            var b = ln((pos + 1.0) / (neg + 1.0))
            var current = loss(a, b)
            repeat(MAX_ITERATIONS) {
                var ga = 0.0
                var gb = 0.0
                var haa = RIDGE
                var hab = 0.0
                var hbb = RIDGE
                for (i in z.indices) {
                    val p = sigmoid(a * z[i] + b)
                    val d = p - t[i]
                    val w = p * (1 - p)
                    ga += d * z[i]
                    gb += d
                    haa += w * z[i] * z[i]
                    hab += w * z[i]
                    hbb += w
                }
                if (abs(ga) < TOLERANCE && abs(gb) < TOLERANCE) return Calibration(a, b)
                val det = haa * hbb - hab * hab
                val da = -(hbb * ga - hab * gb) / det
                val db = -(-hab * ga + haa * gb) / det
                var step = 1.0
                while (step >= MIN_STEP) {
                    val next = loss(a + step * da, b + step * db)
                    if (next < current) {
                        a += step * da
                        b += step * db
                        current = next
                        break
                    }
                    step /= 2
                }
                if (step < MIN_STEP) return Calibration(a, b)
            }
            return Calibration(a, b)
        }

        private fun sigmoid(f: Double): Double = if (f >= 0) 1 / (1 + exp(-f)) else exp(f) / (1 + exp(f))

        /** ln(1 + eˣ). */
        private fun softplus(x: Double): Double = if (x > 0) x + ln(1 + exp(-x)) else ln(1 + exp(x))
    }
}
