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
 * WHERE A SEARCH'S VECTORS LIE, and what it does with them ([Search]): the main memory ([OnHost]),
 * or the card's ([SearchOnCard]). Each of [size] numbers, made once for the whole search.
 */
internal interface Space<V> {
    fun make(size: Int): V

    fun dot(a: V, b: V, n: Int): Float

    /** `y += c · x`. */
    fun axpy(c: Float, x: V, y: V, n: Int)

    /** `x *= c`. */
    fun scale(c: Float, x: V, n: Int)

    fun copy(from: V, into: V, n: Int)

    fun put(from: FloatArray, into: V)

    fun take(from: V, n: Int): FloatArray
}

/** A search's vectors in the main memory, worked in the wide registers. */
internal object OnHost : Space<Floats> {
    override fun make(size: Int) = Floats(size)

    override fun dot(a: Floats, b: Floats, n: Int) = Simd.dot(a, 0, b, 0, n)

    override fun axpy(c: Float, x: Floats, y: Floats, n: Int) = Simd.axpy(c, x, 0, y, 0, n)

    override fun scale(c: Float, x: Floats, n: Int) {
        for (k in 0 until n) x[k] = x[k] * c
    }

    override fun copy(from: Floats, into: Floats, n: Int) = into.copy(from, 0, 0, n)

    override fun put(from: FloatArray, into: Floats) = into.put(from, 0, 0, from.size)

    override fun take(from: Floats, n: Int) = FloatArray(n).also { from.take(it, 0, 0, n) }
}

/** Where a search's point is written for the function to be worked out at, and where its gradient is read from. */
internal interface Place<V> {
    /** [x] written there. */
    fun write(x: V)

    /** `x + t · d` written there: a point along a direction. */
    fun writeStep(x: V, t: Float, d: V)

    /** What is there read into [into]. */
    fun read(into: V)
}

/**
 * Where the numbers of one model's point — or its gradient — lie among the products of many: its
 * pieces, each [count] numbers at [at] of [floats], read one after another as one vector ([Search]).
 */
internal class Pieces(val floats: Array<Floats>, val at: IntArray, val count: IntArray) : Place<Floats> {
    val size = count.sum()

    override fun write(x: Floats) {
        var k = 0
        for (p in floats.indices) {
            floats[p].copy(x, k, at[p], count[p])
            k += count[p]
        }
    }

    override fun writeStep(x: Floats, t: Float, d: Floats) {
        write(x)
        var k = 0
        for (p in floats.indices) {
            Simd.axpy(t, d, k, floats[p], at[p], count[p])
            k += count[p]
        }
    }

    override fun read(into: Floats) {
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
 * in its [space] — the main memory or the card's: the point, the gradient, the direction, the
 * remembered steps and changes, made once for the whole search and reused — nothing made a step.
 */
internal class Search<V>(private val space: Space<V>, private val size: Int, private val maxSteps: Int) {
    /** The point reached: the minimum once [done]. */
    private val x = space.make(size)
    private val g = space.make(size)
    private val d = space.make(size)
    private val steps = List(Lbfgs.MEMORY) { space.make(size) }
    private val changes = List(Lbfgs.MEMORY) { space.make(size) }
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
    fun begin(start: FloatArray) = space.put(start, x)

    /** The point reached, read out once the search has ended. */
    fun result(): FloatArray = space.take(x, size)

    /** The point the search wants the function at, written into [point]. */
    fun propose(point: Place<V>) = if (!started) point.write(x) else point.writeStep(x, t, d)

    /** The function at the point proposed: [loss], its gradient in [grad]; the [point] still holding it. */
    fun answer(loss: Float, point: Place<V>, grad: Place<V>) {
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
        space.axpy(-1f, x, s, size)
        grad.read(y)
        space.axpy(-1f, g, y, size)
        val sy = space.dot(s, y, size)
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
        val gNorm = sqrt(space.dot(g, g, size))
        if (gNorm == 0f) return false
        space.copy(g, d, size)
        for (r in remembered - 1 downTo 0) {
            val k = (oldest + r) % Lbfgs.MEMORY
            alphas[r] = rhos[k] * space.dot(steps[k], d, size)
            space.axpy(-alphas[r], changes[k], d, size)
        }
        val gamma = if (remembered == 0) 1f / gNorm else {
            val k = (oldest + remembered - 1) % Lbfgs.MEMORY
            space.dot(steps[k], changes[k], size) / space.dot(changes[k], changes[k], size)
        }
        space.scale(gamma, d, size)
        for (r in 0 until remembered) {
            val k = (oldest + r) % Lbfgs.MEMORY
            val beta = rhos[k] * space.dot(changes[k], d, size)
            space.axpy(alphas[r] - beta, steps[k], d, size)
        }
        space.scale(-1f, d, size)
        slope = space.dot(g, d, size)
        if (slope >= 0) {
            // Not a descent: the history is forgotten, the gradient followed
            remembered = 0
            oldest = 0
            space.copy(g, d, size)
            space.scale(-1f / gNorm, d, size)
            slope = space.dot(g, d, size)
        }
        t = 1f
        return true
    }
}
