package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * The weights of one model ([Bilinear]): [a] the item's features to its [rank] coordinates
 * (itemWidth × rank), [b] the value's features to its coordinates (valueWidth × rank), [e] every
 * value's own coordinates and [bias] its own bias (by [Design.valueKeys]), [pair] the weights of
 * the pair's own features — the site gives the value as an example, the texts name it — and the
 * common bias.
 */
class Weights(val rank: Int, val a: FloatArray, val b: FloatArray, val e: FloatArray, val bias: FloatArray, val pair: FloatArray) {
    fun write(out: DataOutputStream) {
        out.writeInt(rank)
        listOf(a, b, e, bias, pair).forEach { Design.writeFloats(out, it) }
    }

    companion object {
        fun read(input: DataInputStream): Weights {
            val rank = input.readInt()
            return Weights(rank, Design.readFloats(input), Design.readFloats(input), Design.readFloats(input), Design.readFloats(input), Design.readFloats(input))
        }
    }
}

/**
 * A task as the training reads it: the features of every item and of every value, the label and
 * the weight of every pair of them, the pairs' own features, and the fold every item is held out in.
 *
 * A pair's label: whether the item has the value — the site's or the user's word. Its weight: 1 for
 * a value the item has and for one the user rejected; [SuggestionTask.lackWeight] for a value the
 * item merely lacks; nought for a value the item may not have at all ([SuggestionTask.allowed]: a
 * character of another universe), for every value an item without any value of the facet lacks —
 * such an item is unknown, not denied.
 */
internal class Problem(val task: SuggestionTask, val design: Design, val folds: Int) {
    val n = task.itemCount
    val m = task.valueCount
    val x: Matrix = design.items(task, IntArray(n) { it })
    val z: Matrix = design.values(task)
    val label = Floats(n * m)
    val weight = Floats(n * m)
    /** The values the site gives each item as an example: the site's word, an input of the pair. */
    val examples: List<IntArray> = task.examples
    /** The values each item's texts name, with log(1 + how many times). */
    val mentions: List<Pair<IntArray, FloatArray>> = task.mentions.map { named ->
        val list = named.orEmpty().entries.filter { it.key in 0 until m }
        list.map { it.key }.toIntArray() to FloatArray(list.size) { ln1p(list[it].value.toDouble()).toFloat() }
    }
    val foldOf = IntArray(n) { foldOf(task.itemIds[it], folds) }

    init {
        val row = FloatArray(m)
        val labels = FloatArray(m)
        for (i in 0 until n) {
            row.fill(0f)
            labels.fill(0f)
            val has = task.assigned[i]
            if (has.isNotEmpty()) {
                val lack = task.lackWeight.toFloat()
                val allowed = task.allowed[i]
                if (allowed == null) row.fill(lack) else allowed.forEach { if (it in 0 until m) row[it] = lack }
            }
            task.rejected[i].forEach { row[it] = 1f }
            has.forEach { row[it] = 1f; labels[it] = 1f }
            weight.put(row, 0, i * m, m)
            label.put(labels, 0, i * m, m)
        }
    }

    companion object {
        /** The fold of an item: the same every time, whatever else the task holds. */
        fun foldOf(id: String, folds: Int): Int = (id.hashCode() and Int.MAX_VALUE) % folds
    }
}

/**
 * ONE MODEL OF EVERY ITEM AND EVERY VALUE: the chance an item has a value is
 *
 *     σ( (xᵢ A) · (z_v B + e_v) + b_v + w · p_iv )
 *
 * xᵢ the item's features ([Design]: every input block — the title, the description, the lines of the
 * site, the reviews, the chapters, the pictures — and its other facets' values), z_v the value's
 * (its name, its groups), e_v and b_v the value's own coordinates and bias, p_iv the pair's own
 * features. Both sides are carried to [rank] coordinates by A and B, learnt together: a value never
 * given to any item is judged by its name and its groups, a frequent one by its examples too.
 *
 * Learnt by the weighted logistic loss over every pair ([Problem]) with an L2 penalty [lambda] on
 * every weight, minimised by L-BFGS from a small random start.
 */
internal class Bilinear(private val p: Problem, val rank: Int) {
    private val n = p.n
    private val m = p.m
    private val dx = p.design.itemWidth
    private val dz = p.design.valueWidth
    val size = dx * rank + dz * rank + m * rank + m + PAIR

    private val aF = Floats(dx * rank)
    private val bF = Floats(dz * rank)
    private val u = Floats(n * rank)
    private val v = Floats(m * rank)
    private val vt = Floats(rank * m)
    private val s = Floats(n * m)
    private val g = Floats(n * m)
    private val du = Floats(n * rank)
    private val dv = Floats(m * rank)
    private val da = Floats(dx * rank)
    private val db = Floats(dz * rank)

    /**
     * The start: A and B nought, so a weight grows only where the data lead it — along a direction
     * no item varies in (titles alike) it stays nought, and an item unlike the rest there is not
     * judged by a random weight; the own coordinates of the values the training has pairs of small
     * and random, for the gradient not to be nought everywhere as at the very origin.
     */
    fun start(seed: Long): DoubleArray {
        val random = Random(seed)
        val out = DoubleArray(size)
        val used = BooleanArray(m)
        val w = FloatArray(m)
        for (i in 0 until n) {
            p.weight.take(w, 0, i * m, m)
            for (k in 0 until m) if (w[k] != 0f) used[k] = true
        }
        val eAt = dx * rank + dz * rank
        val se = 0.1 / sqrt(rank.toDouble())
        for (v in 0 until m) if (used[v]) for (q in 0 until rank) out[eAt + v * rank + q] = random.nextGaussian() * se
        return out
    }

    /** The weights minimising the loss over the items not of [heldOut] (-1: every item), from [from]. */
    fun fit(from: DoubleArray, lambda: Double, heldOut: Int, maxSteps: Int = MAX_STEPS): DoubleArray =
        Lbfgs.minimize(from, maxSteps) { theta, grad -> lossAndGradient(theta, grad, lambda) { p.foldOf[it] != heldOut } }

    /** The weighted losses of held-out pairs, summed: Σw, Σw·l, Σw·l², Σw². */
    class Held(var weight: Double = 0.0, var loss: Double = 0.0, var squares: Double = 0.0, var weights: Double = 0.0) {
        val mean: Double get() = if (weight > 0) loss / weight else Double.MAX_VALUE

        /** The standard error of [mean]: the weighted spread over the effective number of pairs. */
        val error: Double get() {
            if (weight <= 0 || weights <= 0) return Double.MAX_VALUE
            val variance = (squares / weight - mean * mean).coerceAtLeast(0.0)
            return kotlin.math.sqrt(variance / (weight * weight / weights))
        }
    }

    /** The weighted losses of the pairs of the items of [fold] by [theta], added to [into]. */
    fun heldOutLoss(theta: DoubleArray, fold: Int, into: Held) {
        scores(theta)
        val pair = theta.copyOfRange(size - PAIR, size)
        val bias = size - PAIR - m
        val row = FloatArray(m)
        val w = FloatArray(m)
        val y = FloatArray(m)
        for (i in 0 until n) {
            if (p.foldOf[i] != fold) continue
            s.take(row, 0, i * m, m)
            p.weight.take(w, 0, i * m, m)
            p.label.take(y, 0, i * m, m)
            addPair(i, row, pair)
            for (k in 0 until m) {
                if (w[k] == 0f) continue
                val z = row[k] + theta[bias + k]
                val l = softplus(z) - y[k] * z
                into.weight += w[k]
                into.loss += w[k] * l
                into.squares += w[k] * l * l
                into.weights += w[k].toDouble() * w[k]
            }
        }
    }

    /** S = (X A)(Z B + E)ᵀ into [s]; U and V kept for the gradient. */
    private fun scores(theta: DoubleArray) {
        for (k in 0 until dx * rank) aF[k] = theta[k].toFloat()
        val bAt = dx * rank
        for (k in 0 until dz * rank) bF[k] = theta[bAt + k].toFloat()
        Blas.product(p.x.held, dx, n, dx, aF, rank, u)
        Blas.product(p.z.held, dz, m, dz, bF, rank, v)
        val eAt = bAt + dz * rank
        for (k in 0 until m * rank) v[k] = v[k] + theta[eAt + k].toFloat()
        for (j in 0 until m) for (q in 0 until rank) vt[q * m + j] = v[j * rank + q]
        Blas.product(u, rank, n, rank, vt, m, s)
    }

    /** The pair's own features added to the item's row of scores. */
    private fun addPair(i: Int, row: FloatArray, pair: DoubleArray) {
        p.examples[i].forEach { row[it] += pair[0].toFloat() }
        val (named, counts) = p.mentions[i]
        named.forEachIndexed { k, v -> row[v] += (pair[1] * counts[k]).toFloat() }
        val common = pair[2].toFloat()
        for (k in row.indices) row[k] += common
    }

    private fun lossAndGradient(theta: DoubleArray, grad: DoubleArray, lambda: Double, trains: (Int) -> Boolean): Double {
        scores(theta)
        val pair = theta.copyOfRange(size - PAIR, size)
        val bias = size - PAIR - m
        // The total weight first: the loss is a mean, so the penalty means the same at any size of the task
        var total = 0.0
        val w = FloatArray(m)
        for (i in 0 until n) if (trains(i)) { p.weight.take(w, 0, i * m, m); for (k in 0 until m) total += w[k] }
        if (total == 0.0) total = 1.0
        var loss = 0.0
        val row = FloatArray(m)
        val y = FloatArray(m)
        val gr = FloatArray(m)
        val gBias = DoubleArray(m)
        var gExample = 0.0
        var gMention = 0.0
        var gCommon = 0.0
        for (i in 0 until n) {
            gr.fill(0f)
            if (trains(i)) {
                s.take(row, 0, i * m, m)
                p.weight.take(w, 0, i * m, m)
                p.label.take(y, 0, i * m, m)
                addPair(i, row, pair)
                for (k in 0 until m) {
                    if (w[k] == 0f) continue
                    val z = row[k] + theta[bias + k]
                    loss += w[k] * (softplus(z) - y[k] * z)
                    val d = w[k] * (sigmoid(z) - y[k]) / total
                    gr[k] = d.toFloat()
                    gBias[k] += d
                    gCommon += d
                }
                p.examples[i].forEach { gExample += gr[it] }
                val (named, counts) = p.mentions[i]
                named.forEachIndexed { k, v -> gMention += gr[v] * counts[k] }
            }
            g.put(gr, 0, i * m, m)
        }
        loss /= total
        // dU = G V, dV = Gᵀ U, dA = Xᵀ dU, dB = Zᵀ dV, dE = dV
        Blas.product(g, m, n, m, v, rank, du)
        Blas.transposedProduct(g, m, n, m, u, rank, dv)
        Blas.transposedProduct(p.x.held, dx, n, dx, du, rank, da)
        Blas.transposedProduct(p.z.held, dz, m, dz, dv, rank, db)
        val bAt = dx * rank
        val eAt = bAt + dz * rank
        for (k in 0 until dx * rank) grad[k] = da[k].toDouble()
        for (k in 0 until dz * rank) grad[bAt + k] = db[k].toDouble()
        for (k in 0 until m * rank) grad[eAt + k] = dv[k].toDouble()
        for (k in 0 until m) grad[bias + k] = gBias[k]
        grad[size - PAIR] = gExample
        grad[size - PAIR + 1] = gMention
        grad[size - PAIR + 2] = gCommon
        // The penalty on every weight: the pair's own too — a few answers on the site's examples
        // all of one kind would make the example's weight endless; but the common bias, the base
        // rate of every value, which the values' own biases are counted from
        var penalty = 0.0
        for (k in 0 until size - 1) {
            penalty += theta[k] * theta[k]
            grad[k] += 2 * lambda * theta[k]
        }
        return loss + lambda * penalty
    }

    /** The weights [theta] as kept. */
    fun weights(theta: DoubleArray): Weights {
        fun part(from: Int, count: Int) = FloatArray(count) { theta[from + it].toFloat() }
        val bAt = dx * rank
        val eAt = bAt + dz * rank
        val biasAt = eAt + m * rank
        return Weights(rank, part(0, dx * rank), part(bAt, dz * rank), part(eAt, m * rank), part(biasAt, m), part(size - PAIR, PAIR))
    }

    companion object {
        /**
         * The pair's own features: the site gives the value as an example; the texts name it; and
         * the common bias — a value no item had yet is as likely as values are.
         */
        const val PAIR = 3
        const val MAX_STEPS = 300

        fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))
        fun softplus(z: Double) = if (z > 30) z else ln(1.0 + exp(z))
    }
}
