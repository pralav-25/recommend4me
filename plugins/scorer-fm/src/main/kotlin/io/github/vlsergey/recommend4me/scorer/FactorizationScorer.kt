package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.optim.Lbfgs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.max

/**
 * PAIRWISE FACTORIZATION MACHINE (Rendle, 2010): the line of the pairwise ranking and, besides it,
 * every two features meeting —
 *
 *     score = w·x + Σ_{a<b} ⟨v_a, v_b⟩ · x_a · x_b
 *
 * A tag that counts only beside an annotation of some kind, two tags that count only together:
 * what a line cannot hold. Every feature has a vector of [RANK] numbers, and two features meet by
 * the product of their vectors — so the meetings of thousands of features are learnt from a few
 * hundred grades: by what each feature has in common with the others, not by a weight of every pair.
 *
 * Learnt from the same pairs ([RankingPairs]) by the same loss as the line, with the same L2
 * penalty, on w and on the vectors; the parameter is its C. The vectors start small and random,
 * the same every time: at nought they would have no gradient and stay there. With a strong penalty
 * the vectors shrink to nought and the machine is the line.
 *
 * Every product is one call of OpenBLAS: X·w, X·V and X²·V² for the scores, Xᵀ·(q∘XV) and
 * (X²)ᵀ·q for the gradient; one part each — the cross-validation fills the cores with fits.
 */
class FactorizationScorer : Scorer {
    override val id = "factorization-machine"
    override val title = Label(
        "Factorization machine: features and their pairs",
        "Факторизационная машина: признаки и их пары",
        "Faktorizaciona mašina: obeležja i njihovi parovi",
    )
    override val candidates = listOf(0.01, 0.03, 0.1, 0.3, 1.0)
    override val defaultParameter = 0.1

    override fun fit(task: RankingTask, rows: IntArray, parameter: Double): FittedScorer =
        fit(task.x, RankingPairs.of(task, rows), parameter)

    override fun unpack(bytes: ByteArray): FittedScorer = FactorizationModel.unpack(bytes)

    companion object {
        /** The length of every feature's vector. */
        const val RANK = 8

        /** The spread of the vectors at the start: small beside the line, not nought. */
        private const val START = 0.01

        fun fit(x: Matrix, pairs: RankingPairs, c: Double, rank: Int = RANK, maxIter: Int = 300, tol: Double = 1e-4): FactorizationModel {
            val objective = FactorizationObjective(x, pairs, c, rank)
            val solver = Lbfgs(objective.size, maxIter, tol)
            // The vectors start small and random, the same every time; w at nought
            val random = Random(1)
            for (k in x.cols until objective.size) {
                solver.at[k] = random.nextGaussian() * START
                solver.theta[k] = solver.at[k]
            }
            val g = DoubleArray(objective.size + 1)
            while (!solver.done) solver.tell(objective.at(solver.at, g), g)
            val d = x.cols
            return FactorizationModel(
                FloatArray(d) { solver.theta[it].toFloat() },
                FloatArray(d * rank) { solver.theta[d + it].toFloat() },
                rank,
            )
        }
    }
}

/**
 * The loss of the machine over the pairs and its gradient: θ is w (d numbers), then V (d × rank,
 * a row a feature); the solver's one number more, an intercept, is not used.
 */
internal class FactorizationObjective(val x: Matrix, val pairs: RankingPairs, val c: Double, val rank: Int) {
    private val n = x.rows
    private val d = x.cols
    val size = d + d * rank
    private val count = max(pairs.weight, 1.0)
    private val squares = FactorizationModel.squares(x, n)
    private val w = Floats(d)
    private val v = Floats(d * rank)
    private val v2 = Floats(d * rank)
    private val z = Floats(n)
    private val u = Floats(n * rank)
    private val p = Floats(n * rank)
    private val q = Floats(n)
    private val qu = Floats(n * rank)
    private val gw = Floats(d)
    private val gv = Floats(d * rank)
    private val s = Floats(d)

    fun at(th: DoubleArray, g: DoubleArray): Double {
        for (k in 0 until d) w[k] = th[k].toFloat()
        for (k in 0 until d * rank) {
            val vk = th[d + k].toFloat()
            v[k] = vk
            v2[k] = vk * vk
        }
        FactorizationModel.scores(x, squares, n, w, v, v2, rank, z, u, p)
        q.clear()
        val loss = pairs.loss(z, q)
        // ∂/∂w = Xᵀq; ∂/∂v_jf = Σ_i q_i (x_ij·(XV)_if − v_jf·x_ij²) = (Xᵀ(q∘XV))_jf − v_jf·((X²)ᵀq)_j
        Blas.transposedTimes(x.held, d, n, d, q, gw)
        for (i in 0 until n) {
            val qi = q[i]
            for (f in 0 until rank) qu[i * rank + f] = qi * u[i * rank + f]
        }
        Blas.transposedProduct(x.held, d, n, d, qu, rank, gv, parts = 1)
        Blas.transposedTimes(squares, d, n, d, q, s)
        var penalty = 0.0
        for (k in 0 until d) {
            penalty += th[k] * th[k]
            g[k] = (gw[k] + th[k] / c) / count
        }
        for (j in 0 until d) {
            val sj = s[j]
            for (f in 0 until rank) {
                val k = j * rank + f
                val vk = th[d + k]
                penalty += vk * vk
                g[d + k] = (gv[k] - vk * sj + vk / c) / count
            }
        }
        g[size] = 0.0
        return (loss + 0.5 * penalty / c) / count
    }
}

/** A fitted machine: w, and the vector of every feature ([v], d × [rank], a row a feature). */
class FactorizationModel(val w: FloatArray, val v: FloatArray, val rank: Int) : FittedScorer {
    private val wv = Floats.of(w)
    private val vv = Floats.of(v)
    private val vv2 = Floats.of(FloatArray(v.size) { v[it] * v[it] })

    override fun scores(x: Matrix, rows: Int): FloatArray {
        check(x.cols == w.size) { "The machine has ${w.size} weights for rows of ${x.cols}" }
        val z = Floats(rows)
        scores(x, squares(x, rows), rows, wv, vv, vv2, rank, z, Floats(rows * rank), Floats(rows * rank))
        return z.toArray()
    }

    /** float32 little-endian: the number of features, the rank, w, V. */
    override fun pack(): ByteArray {
        val buffer = ByteBuffer.allocate(2 * Int.SIZE_BYTES + (w.size + v.size) * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(w.size).putInt(rank)
        buffer.asFloatBuffer().put(w).put(v)
        return buffer.array()
    }

    companion object {
        fun unpack(bytes: ByteArray): FactorizationModel {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val d = buffer.getInt()
            val rank = buffer.getInt()
            val floats = buffer.asFloatBuffer()
            val w = FloatArray(d).also { floats.get(it) }
            val v = FloatArray(d * rank).also { floats.get(it) }
            return FactorizationModel(w, v, rank)
        }

        /** The squares of the first [rows] rows of [x], a row each. */
        internal fun squares(x: Matrix, rows: Int): Floats {
            val d = x.cols
            val out = Floats(rows * d)
            val row = FloatArray(d)
            for (i in 0 until rows) {
                x.held.take(row, 0, x.row(i), d)
                for (k in 0 until d) row[k] *= row[k]
                out.put(row, 0, i * d, d)
            }
            return out
        }

        /**
         * The scores of the first [rows] rows of [x] into [z], by Rendle's identity
         * Σ_{a<b} ⟨v_a, v_b⟩ x_a x_b = ½ Σ_f ((x·v_f)² − x²·v_f²); [u] = XV and [p] = X²V² kept for the gradient.
         */
        internal fun scores(x: Matrix, squares: Floats, rows: Int, w: Floats, v: Floats, v2: Floats, rank: Int, z: Floats, u: Floats, p: Floats) {
            val d = x.cols
            Blas.times(x.held, d, rows, d, w, z, parts = 1)
            Blas.product(x.held, d, rows, d, v, rank, u, parts = 1)
            Blas.product(squares, d, rows, d, v2, rank, p, parts = 1)
            for (i in 0 until rows) {
                var pairs = 0f
                for (f in 0 until rank) {
                    val uf = u[i * rank + f]
                    pairs += uf * uf - p[i * rank + f]
                }
                z[i] = z[i] + 0.5f * pairs
            }
        }
    }
}
