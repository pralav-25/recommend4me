package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Simd
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The minimum of a smooth function by limited-memory BFGS (Nocedal & Wright, "Numerical
 * Optimization", algorithms 7.4 and 7.5): the last [HISTORY] steps and changes of the gradient
 * stand for the inverse Hessian; each step is searched back from the full one until the function
 * falls enough (Armijo).
 *
 * In single precision, as the model is. [f] writes the gradient at its point into its second
 * argument and answers the value there. The search stops when the value falls by less than a
 * float can tell of it ([RESOLUTION]), when the gradient is nought, when a step no longer moves
 * the point, or after [maxSteps] steps.
 */
object Lbfgs {
    /** The steps remembered: Nocedal & Wright advise 3 to 20; the middle of it. */
    private const val HISTORY = 10

    /** The sufficient fall of the Armijo condition, c₁ of Nocedal & Wright (3.4). */
    private const val ARMIJO = 1e-4f

    /** The least relative difference of two floats: a fall below it of the value is no fall a float can tell. */
    private val RESOLUTION = Math.ulp(1f)

    /** [stop]: asked before every step; once it says yes the search is given up with a [CancellationException]. */
    fun minimize(start: FloatArray, maxSteps: Int, stop: () -> Boolean = { false }, f: (FloatArray, FloatArray) -> Float): FloatArray {
        val n = start.size
        val x = start.copyOf()
        val g = FloatArray(n)
        var fx = f(x, g)
        val steps = ArrayDeque<FloatArray>()
        val changes = ArrayDeque<FloatArray>()
        val rhos = ArrayDeque<Float>()
        val next = FloatArray(n)
        val gNext = FloatArray(n)
        repeat(maxSteps) { step ->
            if (stop()) throw CancellationException("the search is given up")
            val gNorm = norm(g)
            if (gNorm == 0f) return x
            // The direction: minus the gradient times the inverse Hessian the history stands for
            val d = g.copyOf()
            val alphas = FloatArray(steps.size)
            for (k in steps.indices.reversed()) {
                alphas[k] = rhos[k] * dot(steps[k], d)
                axpy(-alphas[k], changes[k], d)
            }
            val gamma = if (steps.isEmpty()) 1f / gNorm else dot(steps.last(), changes.last()) / dot(changes.last(), changes.last())
            for (i in 0 until n) d[i] *= gamma
            for (k in steps.indices) {
                val beta = rhos[k] * dot(changes[k], d)
                axpy(alphas[k] - beta, steps[k], d)
            }
            for (i in 0 until n) d[i] = -d[i]
            var slope = dot(g, d)
            if (slope >= 0) {
                // Not a descent: the history is forgotten, the gradient followed
                steps.clear(); changes.clear(); rhos.clear()
                for (i in 0 until n) d[i] = -g[i] / gNorm
                slope = dot(g, d)
            }
            var t = 1f
            var fNext: Float
            while (true) {
                for (i in 0 until n) next[i] = x[i] + t * d[i]
                fNext = f(next, gNext)
                // A step too short to move the point by what a float can tell is the last one tried
                if (fNext <= fx + ARMIJO * t * slope || t < RESOLUTION) break
                // Backtracking by halves: ρ = ½ of Nocedal & Wright, algorithm 3.1
                t *= 0.5f
            }
            val s = FloatArray(n) { next[it] - x[it] }
            val y = FloatArray(n) { gNext[it] - g[it] }
            // Only a step of positive curvature stands for the Hessian
            val sy = dot(s, y)
            if (sy > 0f) {
                steps.addLast(s); changes.addLast(y); rhos.addLast(1f / sy)
                if (steps.size > HISTORY) { steps.removeFirst(); changes.removeFirst(); rhos.removeFirst() }
            }
            val fell = fx - fNext
            next.copyInto(x)
            gNext.copyInto(g)
            fx = fNext
            if (step > 0 && abs(fell) <= RESOLUTION * max(1f, abs(fx))) return x
        }
        return x
    }

    // Hundreds of thousands of weights, dozens of times a step: in the wide registers
    private fun dot(a: FloatArray, b: FloatArray): Float = Simd.dot(a, b)

    private fun norm(a: FloatArray) = sqrt(dot(a, a))

    private fun axpy(k: Float, x: FloatArray, y: FloatArray) = Simd.axpy(k, x, y)
}
