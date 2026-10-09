package io.github.vlsergey.recommend4me.suggestion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The minimum of a smooth function by limited-memory BFGS (Nocedal & Wright, "Numerical
 * Optimization", algorithms 7.4 and 7.5): the last [HISTORY] steps and changes of the gradient
 * stand for the inverse Hessian; each step is searched back from the full one until the function
 * falls enough (Armijo).
 *
 * [f] writes the gradient at its point into its second argument and answers the value there.
 * The search stops when the value falls by less than [TOLERANCE] of itself, when the gradient is
 * nought, or after [maxSteps] steps.
 */
object Lbfgs {
    private const val HISTORY = 10
    private const val TOLERANCE = 1e-7
    private const val ARMIJO = 1e-4

    fun minimize(start: DoubleArray, maxSteps: Int, f: (DoubleArray, DoubleArray) -> Double): DoubleArray {
        val n = start.size
        val x = start.copyOf()
        val g = DoubleArray(n)
        var fx = f(x, g)
        val steps = ArrayDeque<DoubleArray>()
        val changes = ArrayDeque<DoubleArray>()
        val rhos = ArrayDeque<Double>()
        val next = DoubleArray(n)
        val gNext = DoubleArray(n)
        repeat(maxSteps) { step ->
            if (norm(g) == 0.0) return x
            // The direction: minus the gradient times the inverse Hessian the history stands for
            val d = g.copyOf()
            val alphas = DoubleArray(steps.size)
            for (k in steps.indices.reversed()) {
                alphas[k] = rhos[k] * dot(steps[k], d)
                axpy(-alphas[k], changes[k], d)
            }
            val gamma = if (steps.isEmpty()) 1.0 / max(norm(g), 1e-12) else dot(steps.last(), changes.last()) / dot(changes.last(), changes.last())
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
                for (i in 0 until n) d[i] = -g[i] / max(norm(g), 1e-12)
                slope = dot(g, d)
            }
            var t = 1.0
            var fNext: Double
            while (true) {
                for (i in 0 until n) next[i] = x[i] + t * d[i]
                fNext = f(next, gNext)
                if (fNext <= fx + ARMIJO * t * slope || t < 1e-12) break
                t *= 0.5
            }
            val s = DoubleArray(n) { next[it] - x[it] }
            val y = DoubleArray(n) { gNext[it] - g[it] }
            val sy = dot(s, y)
            if (sy > 1e-12) {
                steps.addLast(s); changes.addLast(y); rhos.addLast(1.0 / sy)
                if (steps.size > HISTORY) { steps.removeFirst(); changes.removeFirst(); rhos.removeFirst() }
            }
            val fell = fx - fNext
            next.copyInto(x)
            gNext.copyInto(g)
            fx = fNext
            if (step > 0 && abs(fell) <= TOLERANCE * max(1.0, abs(fx))) return x
        }
        return x
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun norm(a: DoubleArray) = sqrt(dot(a, a))

    private fun axpy(k: Double, x: DoubleArray, y: DoubleArray) {
        for (i in x.indices) y[i] += k * x[i]
    }
}
