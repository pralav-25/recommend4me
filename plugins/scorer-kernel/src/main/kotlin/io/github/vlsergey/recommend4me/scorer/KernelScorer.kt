package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.optim.Lbfgs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * PAIRWISE KERNEL RANKING: a work scores by how alike it is to every work learnt from —
 *
 *     score(x) = Σ_j α_j · k(x_j, x)
 *
 * — not by a sum of what each of its features is worth. A taste of several kinds (quiet everyday
 * stories, and hard war ones, and not their mixture) is a score high near either and low between:
 * no line holds it, a kernel does.
 *
 * The kernel is the arc-cosine kernel of degree one (Cho & Saul, 2009), the kernel of a ReLU
 * network of endless width: k(x, y) = |x|·|y|·(sin θ + (π − θ)·cos θ) / π, θ the angle between x
 * and y. It has no width to choose: none is chosen by hand, and none needs a measuring.
 *
 * Learnt from the same pairs ([RankingPairs]) by the same loss as the line, the penalty αᵀKα / 2C:
 * with the linear kernel it is |w|² / 2C, the line's own, so C means what it means there. The
 * kernel's matrices are one product of OpenBLAS each, X·Xᵀ; the gradient is one more, K·(q + α/C).
 */
class KernelScorer : Scorer {
    override val id = "kernel-arccos"
    override val title = "Ядерное ранжирование: похожесть на оценённые работы"
    override val candidates = listOf(0.01, 0.03, 0.1, 0.3, 1.0)
    override val defaultParameter = 0.1

    override fun fit(task: RankingTask, rows: IntArray, parameter: Double): FittedScorer {
        // The rows learnt from: the graded ones of the fit and those without a grade, which have equals
        val learnt = (rows.asSequence() + task.ungraded.asSequence()).distinct().toList().toIntArray()
        val at = IntArray(task.x.rows) { -1 }.also { a -> learnt.forEachIndexed { k, i -> a[i] = k } }
        val all = RankingPairs.of(task, rows)
        // The pairs in the numbering of the rows learnt from
        val pairs = RankingPairs(
            IntArray(all.better.size) { at[all.better[it]] }, IntArray(all.worse.size) { at[all.worse[it]] },
            IntArray(all.first.size) { at[all.first[it]] }, IntArray(all.second.size) { at[all.second[it]] }, all.equalWeight,
        )
        return fit(task.x.gather(learnt), pairs, parameter)
    }

    override fun unpack(bytes: ByteArray): FittedScorer = KernelModel.unpack(bytes)

    companion object {
        fun fit(x: Matrix, pairs: RankingPairs, c: Double, maxIter: Int = 300, tol: Double = 1e-4): KernelModel {
            val objective = KernelObjective(Kernel.of(x, x), x.rows, pairs, c)
            val solver = Lbfgs(x.rows, maxIter, tol)
            val g = DoubleArray(x.rows + 1)
            while (!solver.done) solver.tell(objective.at(solver.at, g), g)
            return KernelModel(x, FloatArray(x.rows) { solver.theta[it].toFloat() })
        }
    }
}

/** The arc-cosine kernel of degree one between the rows of two matrices. */
internal object Kernel {
    /** k(a_i, b_j) for every row i of [a] and j of [b], a row of [a] a row of the result. */
    fun of(a: Matrix, b: Matrix, rows: Int = a.rows): Floats {
        check(a.cols == b.cols) { "Rows of ${a.cols} features against rows of ${b.cols}" }
        val d = a.cols
        val m = b.rows
        // Bᵀ, then A·Bᵀ: every dot product in one call
        val bt = Floats(d * m)
        val row = FloatArray(d)
        for (j in 0 until m) {
            b.held.take(row, 0, b.row(j), d)
            for (k in 0 until d) bt[k * m + j] = row[k]
        }
        val dots = Floats(rows * m)
        Blas.product(a.held, d, rows, d, bt, m, dots, parts = 1)
        val na = norms(a, rows)
        val nb = norms(b, m)
        for (i in 0 until rows) for (j in 0 until m) {
            val scale = na[i] * nb[j]
            dots[i * m + j] = if (scale <= 0.0) 0f else {
                val cos = (dots[i * m + j] / scale).coerceIn(-1.0, 1.0)
                val theta = acos(cos)
                (scale * (sqrt(1 - cos * cos) + (PI - theta) * cos) / PI).toFloat()
            }
        }
        return dots
    }

    private fun norms(x: Matrix, rows: Int): DoubleArray {
        val row = FloatArray(x.cols)
        return DoubleArray(rows) { i ->
            x.held.take(row, 0, x.row(i), x.cols)
            sqrt(row.sumOf { (it * it).toDouble() })
        }
    }
}

/** The loss over the pairs by α, the weights of the rows learnt from, and its gradient. */
internal class KernelObjective(private val k: Floats, private val n: Int, private val pairs: RankingPairs, private val c: Double) {
    private val count = max(pairs.weight, 1.0)
    private val alpha = Floats(n)
    private val z = Floats(n)
    private val q = Floats(n)
    private val kq = Floats(n)

    fun at(th: DoubleArray, g: DoubleArray): Double {
        for (i in 0 until n) alpha[i] = th[i].toFloat()
        Blas.times(k, n, n, n, alpha, z, parts = 1)
        q.clear()
        val loss = pairs.loss(z, q)
        // The penalty αᵀKα/2C = α·z/2C; the gradient of both, K·(q + α/C)
        var penalty = 0.0
        for (i in 0 until n) {
            penalty += th[i] * z[i]
            q[i] = q[i] + (th[i] / c).toFloat()
        }
        Blas.times(k, n, n, n, q, kq, parts = 1)
        for (i in 0 until n) g[i] = kq[i] / count
        g[n] = 0.0
        return (loss + 0.5 * penalty / c) / count
    }
}

/** A fitted kernel ranking: the rows learnt from and the weight of each. */
class KernelModel(val learnt: Matrix, val alpha: FloatArray) : FittedScorer {
    private val av = Floats.of(alpha)

    override fun scores(x: Matrix, rows: Int): FloatArray {
        check(x.cols == learnt.cols) { "The model learnt from rows of ${learnt.cols} features, not of ${x.cols}" }
        val k = Kernel.of(x, learnt, rows)
        val out = Floats(rows)
        Blas.times(k, learnt.rows, rows, learnt.rows, av, out, parts = 1)
        return out.toArray()
    }

    /** float32 little-endian: the rows learnt from and their features, the rows, α. */
    override fun pack(): ByteArray {
        val m = learnt.rows
        val d = learnt.cols
        val buffer = ByteBuffer.allocate(2 * Int.SIZE_BYTES + (m * d + m) * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(m).putInt(d)
        val floats = buffer.asFloatBuffer()
        floats.put(learnt.held.toArray()).put(alpha)
        return buffer.array()
    }

    companion object {
        fun unpack(bytes: ByteArray): KernelModel {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val m = buffer.getInt()
            val d = buffer.getInt()
            val floats = buffer.asFloatBuffer()
            val x = Matrix(m, d)
            x.held.put(FloatArray(m * d).also { floats.get(it) }, 0, 0, m * d)
            return KernelModel(x, FloatArray(m).also { floats.get(it) })
        }
    }
}
