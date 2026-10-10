package io.github.vlsergey.recommend4me.optim

import io.github.vlsergey.recommend4me.matrix.Simd
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * L-BFGS with a backtracking (Armijo) line search, in ask/tell form: it says where it wants
 * the loss and the gradient next ([at]), is told them ([tell]) and says when it is [done]. Over
 * [d] weights and one more number, an intercept for those who need it. Ported from
 * local-ai-chroma-enf-lora's `student/Optim.kt`.
 */
class Lbfgs(private val d: Int, private val maxIter: Int, private val tol: Double) {
    val at = DoubleArray(d + 1)
    val theta = DoubleArray(d + 1)

    var done = false
        private set

    private val g = DoubleArray(d + 1)
    private val dir = DoubleArray(d + 1)
    private val memory = LbfgsMemory()
    private var started = false
    private var f = 0.0
    private var iter = 0
    private var step = 0.0
    private var slope = 0.0
    private var attempt = 0

    fun tell(loss: Double, gradient: DoubleArray) {
        if (done) return
        if (!started) {
            started = true
            gradient.copyInto(g)
            f = loss
            iterate()
            return
        }
        if (loss <= f + 1e-4 * step * slope) accept(loss, gradient) else halve()
    }

    private fun accept(loss: Double, gradient: DoubleArray) {
        memory.remember(DoubleArray(d + 1) { at[it] - theta[it] }, DoubleArray(d + 1) { gradient[it] - g[it] })
        val fOld = f
        at.copyInto(theta)
        gradient.copyInto(g)
        f = loss
        if (fOld - loss <= FTOL * max(max(abs(fOld), abs(loss)), 1.0)) {
            done = true
            return
        }
        iter++
        iterate()
    }

    private fun halve() {
        if (++attempt >= 40) {
            done = true
            return
        }
        step *= 0.5
        for (k in at.indices) at[k] = theta[k] + step * dir[k]
    }

    private fun iterate() {
        if (iter >= maxIter || g.maxOf { abs(it) } <= tol) {
            done = true
            return
        }
        val history = memory.size
        memory.direction(g, dir)
        slope = dot(g, dir)
        if (slope >= 0) {
            // Not a descent direction: the history lies, start afresh
            memory.forget()
            for (k in 0..d) dir[k] = -g[k]
            slope = dot(g, dir)
        }
        step = if (history == 0) min(1.0, 1.0 / sqrt(-slope)) else 1.0
        attempt = 0
        for (k in at.indices) at[k] = theta[k] + step * dir[k]
    }
}

/** The last pairs of steps and gradient changes, and the direction −H·g they give (two-loop recursion). */
private class LbfgsMemory {
    private val sHist = ArrayList<DoubleArray>()
    private val yHist = ArrayList<DoubleArray>()
    val size: Int get() = sHist.size

    fun direction(g: DoubleArray, dir: DoubleArray) {
        for (k in dir.indices) dir[k] = -g[k]
        val h = sHist.size
        val alpha = DoubleArray(h)
        for (j in h - 1 downTo 0) {
            val rho = 1.0 / dot(yHist[j], sHist[j])
            alpha[j] = rho * dot(sHist[j], dir)
            Simd.axpy(-alpha[j], yHist[j], dir)
        }
        if (h > 0) {
            val gamma = dot(sHist[h - 1], yHist[h - 1]) / dot(yHist[h - 1], yHist[h - 1])
            for (k in dir.indices) dir[k] *= gamma
        }
        for (j in 0 until h) {
            val rho = 1.0 / dot(yHist[j], sHist[j])
            val beta = rho * dot(yHist[j], dir)
            Simd.axpy(alpha[j] - beta, sHist[j], dir)
        }
    }

    /** Only steps of positive curvature are remembered, and only the last [MEMORY]. */
    fun remember(s: DoubleArray, y: DoubleArray) {
        if (dot(s, y) <= 1e-12) return
        sHist.add(s)
        yHist.add(y)
        if (sHist.size > MEMORY) {
            sHist.removeAt(0)
            yHist.removeAt(0)
        }
    }

    fun forget() {
        sHist.clear()
        yHist.clear()
    }
}

/** Of thousands of weights, dozens of times a step: in the wide registers. */
private fun dot(a: DoubleArray, b: DoubleArray): Double = Simd.dot(a, b)

private const val MEMORY = 10
private const val FTOL = 64 * 2.220446049250313e-16
