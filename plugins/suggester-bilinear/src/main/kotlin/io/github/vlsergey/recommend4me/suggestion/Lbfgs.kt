package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats
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

    internal const val MEMORY = HISTORY
    internal const val SUFFICIENT = ARMIJO
    internal val FLOAT_RESOLUTION = RESOLUTION
}

/**
 * Where the numbers of one model's point — or its gradient — lie among the products of many: its
 * pieces, each [count] numbers at [at] of [floats], read one after another as one vector ([Lbfgs.Search]).
 */
internal class Pieces(val floats: Array<Floats>, val at: IntArray, val count: IntArray) {
    val size = count.sum()

    /** The numbers of [from] (contiguous, of [size]) written into the pieces. */
    fun write(from: Floats) {
        var k = 0
        for (p in floats.indices) {
            floats[p].copy(from, k, at[p], count[p])
            k += count[p]
        }
    }

    /** `x + t · d` written into the pieces: a point along a direction. */
    fun writeStep(x: Floats, t: Float, d: Floats) {
        write(x)
        var k = 0
        for (p in floats.indices) {
            Simd.axpy(t, d, k, floats[p], at[p], count[p])
            k += count[p]
        }
    }

    /** The pieces read into [into] (contiguous, of [size]). */
    fun read(into: Floats) {
        var k = 0
        for (p in floats.indices) {
            into.copy(floats[p], at[p], k, count[p])
            k += count[p]
        }
    }
}

/**
 * ONE MODEL'S SEARCH FOR ITS MINIMUM, A POINT AT A TIME ([Lbfgs.minimize] as steps of its own): it
 * names the point it wants the function at ([propose]) and is told what the function is there
 * ([answer]) — so that many models' points are worked out in one product of them all, the slowest
 * model's every step not holding up the rest. The same algorithm in single precision, its numbers
 * where the products lie: the point, the gradient, the direction, the remembered steps and changes,
 * made once for the whole search and reused — nothing made a step.
 */
internal class Search(private val size: Int, private val maxSteps: Int) {
    /** The point reached: the minimum once [done]. */
    val x = Floats(size)
    private val g = Floats(size)
    private val d = Floats(size)
    private val steps = Array(Lbfgs.MEMORY) { Floats(size) }
    private val changes = Array(Lbfgs.MEMORY) { Floats(size) }
    private val rhos = FloatArray(Lbfgs.MEMORY)
    private val alphas = FloatArray(Lbfgs.MEMORY)

    /** How many steps are remembered, and where the oldest of them is in the ring. */
    private var remembered = 0
    private var oldest = 0
    private var fx = 0f
    private var slope = 0f
    private var t = 1f
    private var step = 0
    private var started = false

    /** Whether the search has ended: the value no longer falls, the gradient is nought, or [maxSteps] were taken. */
    var done = false
        private set

    /** How many times the function was worked out for it. */
    var evaluations = 0
        private set

    /** The search begun at [start] (contiguous, of the model's size). */
    fun begin(start: FloatArray) {
        x.put(start, 0, 0, size)
    }

    /** The point the search wants the function at, written into [point]. */
    fun propose(point: Pieces) = if (!started) point.write(x) else point.writeStep(x, t, d)

    /** The function at the point proposed: [loss], its gradient in [grad]; the [point] still holding it. */
    fun answer(loss: Float, point: Pieces, grad: Pieces) {
        evaluations++
        if (!started) {
            started = true
            fx = loss
            grad.read(g)
            if (!direction()) done = true
            return
        }
        // A step too short to move the point by what a float can tell is the last one tried
        if (!(loss <= fx + Lbfgs.SUFFICIENT * t * slope || t < Lbfgs.FLOAT_RESOLUTION)) {
            // Backtracking by halves: ρ = ½ of Nocedal & Wright, algorithm 3.1
            t *= 0.5f
            return
        }
        // The step taken and the change of the gradient, into the ring's next place: only a step of
        // positive curvature stands for the Hessian
        val at = (oldest + remembered) % Lbfgs.MEMORY
        val s = steps[at]
        val y = changes[at]
        point.read(s)
        Simd.axpy(-1f, x, 0, s, 0, size)
        grad.read(y)
        Simd.axpy(-1f, g, 0, y, 0, size)
        val sy = Simd.dot(s, 0, y, 0, size)
        if (sy > 0f) {
            rhos[at] = 1f / sy
            if (remembered < Lbfgs.MEMORY) remembered++ else oldest = (oldest + 1) % Lbfgs.MEMORY
        }
        point.read(x)
        grad.read(g)
        val fell = fx - loss
        fx = loss
        step++
        done = (step > 1 && abs(fell) <= Lbfgs.FLOAT_RESOLUTION * max(1f, abs(fx))) || step >= maxSteps || !direction()
    }

    /**
     * The next direction: minus the gradient times the inverse Hessian the remembered steps stand
     * for (two loops, algorithm 7.4), the step back to one; false when the gradient is nought.
     */
    private fun direction(): Boolean {
        val gNorm = sqrt(Simd.dot(g, 0, g, 0, size))
        if (gNorm == 0f) return false
        d.copy(g, 0, 0, size)
        for (r in remembered - 1 downTo 0) {
            val k = (oldest + r) % Lbfgs.MEMORY
            alphas[r] = rhos[k] * Simd.dot(steps[k], 0, d, 0, size)
            Simd.axpy(-alphas[r], changes[k], 0, d, 0, size)
        }
        val gamma = if (remembered == 0) 1f / gNorm else {
            val k = (oldest + remembered - 1) % Lbfgs.MEMORY
            Simd.dot(steps[k], 0, changes[k], 0, size) / Simd.dot(changes[k], 0, changes[k], 0, size)
        }
        scale(d, gamma)
        for (r in 0 until remembered) {
            val k = (oldest + r) % Lbfgs.MEMORY
            val beta = rhos[k] * Simd.dot(changes[k], 0, d, 0, size)
            Simd.axpy(alphas[r] - beta, steps[k], 0, d, 0, size)
        }
        scale(d, -1f)
        slope = Simd.dot(g, 0, d, 0, size)
        if (slope >= 0) {
            // Not a descent: the history is forgotten, the gradient followed
            remembered = 0
            oldest = 0
            d.copy(g, 0, 0, size)
            scale(d, -1f / gNorm)
            slope = Simd.dot(g, 0, d, 0, size)
        }
        t = 1f
        return true
    }

    private fun scale(f: Floats, c: Float) {
        for (k in 0 until size) f[k] = f[k] * c
    }
}
