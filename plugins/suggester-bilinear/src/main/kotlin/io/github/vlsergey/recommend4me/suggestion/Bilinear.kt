package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.matrix.Simd
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Random
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
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

    /**
     * The names of the values as texts of a work ([SuggestionTask.hints]), learnt from in every fold
     * and judged in none: a name read as the work's every text — each block of [hintBlocks] — that
     * names its value and none of its rivals.
     */
    val hints: List<NameHint> = task.hints.filter { h -> design.textBlocks(h.vector.size).isNotEmpty() }
    val hintDim = hints.firstOrNull()?.vector?.size ?: 0
    val hintBlocks: List<Int> = if (hints.isEmpty()) emptyList() else design.textBlocks(hintDim)
    val hintText: Matrix = Matrix(hints.size, hintDim).also { m -> hints.forEachIndexed { k, h -> m.held.put(h.vector, 0, m.row(k), hintDim) } }

    /** Names that are judged against the same values — a universe's characters' names — scored together. */
    class HintGroup(val rows: IntArray, val values: IntArray, val positive: IntArray)

    val hintGroups: List<HintGroup> = hints.indices.groupBy { k -> (hints[k].against + hints[k].value).distinct().sorted() }.map { (values, rows) ->
        val at = values.withIndex().associate { (i, v) -> v to i }
        HintGroup(rows.toIntArray(), values.toIntArray(), IntArray(rows.size) { at.getValue(hints[rows[it]].value) })
    }

    /** How many pairs of a name and a value the names are judged on. */
    val hintPairs: Long = hintGroups.sumOf { it.rows.size.toLong() * it.values.size }

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

    /** The values no item outside [fold] has: what the model fitted without the fold learns of them is their name and groups alone. */
    fun unseenOutside(fold: Int): BooleanArray {
        val unseen = BooleanArray(m) { true }
        val row = FloatArray(m)
        for (i in 0 until n) {
            if (foldOf[i] == fold) continue
            label.take(row, 0, i * m, m)
            for (k in 0 until m) if (row[k] > 0f) unseen[k] = false
        }
        return unseen
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

    // The names as texts of a work ([Problem.hints]): every text block of theirs holds the name, so
    // their coordinates are the name times the text blocks' weights summed, plus the constant's and
    // the blocks' marks'
    private val h = p.hints.size
    private val hd = p.hintDim
    private val uh = Floats(h * rank)
    private val duh = Floats(h * rank)
    private val aSum = Floats(hd * rank)
    private val dSum = Floats(hd * rank)
    private val hintBase = FloatArray(rank)
    private val dvHint = FloatArray(m * rank)
    private val largest = p.hintGroups.maxOfOrNull { it.rows.size } ?: 0
    private val widest = p.hintGroups.maxOfOrNull { it.values.size } ?: 0
    private val gu = Floats(largest * rank)
    private val gv = Floats(widest * rank)
    private val gvt = Floats(rank * widest)
    private val gs = Floats(largest * widest)
    private val gdu = Floats(largest * rank)
    private val gdv = Floats(widest * rank)

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
        p.hints.forEach { hint -> used[hint.value] = true; hint.against.forEach { used[it] = true } }
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

        fun add(w: Double, l: Double) {
            weight += w
            loss += w * l
            squares += w * l * l
            weights += w * w
        }

        /** The standard error of [mean]: the weighted spread over the effective number of pairs. */
        val error: Double get() {
            if (weight <= 0 || weights <= 0) return Double.MAX_VALUE
            val variance = (squares / weight - mean * mean).coerceAtLeast(0.0)
            return kotlin.math.sqrt(variance / (weight * weight / weights))
        }
    }

    /**
     * The weighted losses of the pairs of the items of [fold] by [theta], added to [into]; those of
     * the values no item of the other folds has ([Problem.unseenOutside]) to [unseen] besides — how
     * the model judges a value it learnt nothing of but its name and groups.
     */
    fun heldOutLoss(theta: DoubleArray, fold: Int, into: Held, unseen: Held? = null) {
        val fresh = if (unseen == null) null else p.unseenOutside(fold)
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
                into.add(w[k].toDouble(), l)
                if (fresh != null && fresh[k]) unseen!!.add(w[k].toDouble(), l)
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
        if (h > 0) hintCoordinates()
    }

    /** The coordinates of every name read as a work's every text: Uh = H·Σ_b s_b·A_b + A_const + Σ_b A_mark(b). */
    private fun hintCoordinates() {
        aSum.clear()
        hintBase.fill(0f)
        for (q in 0 until rank) hintBase[q] = aF[q]
        p.hintBlocks.forEach { b ->
            val mark = p.design.blockOffsets[b]
            val scale = p.design.blocks[b].scale
            for (q in 0 until rank) hintBase[q] += aF[mark * rank + q]
            for (c in 0 until hd) for (q in 0 until rank) aSum[c * rank + q] = aSum[c * rank + q] + scale * aF[(mark + 1 + c) * rank + q]
        }
        Blas.product(p.hintText.held, hd, h, hd, aSum, rank, uh)
        for (k in 0 until h) for (q in 0 until rank) uh[k * rank + q] = uh[k * rank + q] + hintBase[q]
    }

    /**
     * The loss of every name read as a text that names its value and none of its rivals, weight 1 a
     * pair; its gradient into [duh], [dvHint] and the biases. Answers the loss summed, not averaged.
     */
    private fun hintLoss(theta: DoubleArray, pair: DoubleArray, total: Double, gBias: DoubleArray, sums: DoubleArray): Double {
        duh.clear()
        dvHint.fill(0f)
        val bias = size - PAIR - m
        val named = ln(2.0)
        var loss = 0.0
        p.hintGroups.forEach { group ->
            val k = group.rows.size
            val c = group.values.size
            // The group's names' and values' coordinates, gathered; their scores by one product
            group.rows.forEachIndexed { r, row -> for (q in 0 until rank) gu[r * rank + q] = uh[row * rank + q] }
            group.values.forEachIndexed { j, value -> for (q in 0 until rank) { gv[j * rank + q] = v[value * rank + q]; gvt[q * c + j] = v[value * rank + q] } }
            Blas.product(gu, rank, k, rank, gvt, c, gs)
            // Every pair's loss and slope, a row of names at a time on every core: a large universe
            // has thousands of names against thousands of characters. Every value of the row is
            // taken against the label nought at once, in the wide registers; the name's own value
            // is then set right: its label is one, and the text names it
            val shift = FloatArray(c) { j -> (theta[bias + group.values[j]] + pair[2]).toFloat() }
            val scale = (1 / total).toFloat()
            val rowLoss = DoubleArray(k)
            val rowSlope = DoubleArray(k)
            val rowNamed = DoubleArray(k)
            // The scores are taken where they lie, in the native memory of the product: not copied out and back
            IntStream.range(0, k).parallel().forEach { r ->
                val own = group.positive[r]
                val at = r * c
                val before = if (own in 0 until c) (gs[at + own] + shift[own]).toDouble() else 0.0
                val sums = DoubleArray(2)
                Simd.logisticRow(gs, at, shift, c, scale, sums)
                if (own in 0 until c) {
                    val z = before + pair[1] * named
                    val d = (sigmoid(z) - 1) / total
                    sums[0] += softplus(z) - z - softplus(before)
                    sums[1] += d - sigmoid(before) / total
                    gs[at + own] = d.toFloat()
                    rowNamed[r] = d * named
                }
                rowLoss[r] = sums[0]
                rowSlope[r] = sums[1]
            }
            loss += rowLoss.sum()
            sums[1] += rowSlope.sum()
            sums[0] += rowNamed.sum()
            // The slope of every value's bias: the sum of its column, in double precision, the columns shared out among the cores
            val columnSums = DoubleArray(c)
            IntStream.range(0, (c + COLUMNS - 1) / COLUMNS).parallel().forEach { part ->
                val from = part * COLUMNS
                val upTo = minOf(c, from + COLUMNS)
                for (r in 0 until k) {
                    val at = r * c
                    for (j in from until upTo) columnSums[j] += gs[at + j]
                }
            }
            for (j in 0 until c) gBias[group.values[j]] += columnSums[j]
            // The gradient of the group's coordinates, scattered back
            Blas.product(gs, c, k, c, gv, rank, gdu)
            Blas.transposedProduct(gs, c, k, c, gu, rank, gdv)
            group.rows.forEachIndexed { r, row -> for (q in 0 until rank) duh[row * rank + q] = duh[row * rank + q] + gdu[r * rank + q] }
            group.values.forEachIndexed { j, value -> for (q in 0 until rank) dvHint[value * rank + q] += gdv[j * rank + q] }
        }
        return loss
    }

    /** The gradient of the names' coordinates carried back into the text blocks' weights, the constant's and the marks'. */
    private fun hintGradient() {
        Blas.transposedProduct(p.hintText.held, hd, h, hd, duh, rank, dSum)
        val base = FloatArray(rank)
        for (k in 0 until h) for (q in 0 until rank) base[q] += duh[k * rank + q]
        for (q in 0 until rank) da[q] = da[q] + base[q]
        p.hintBlocks.forEach { b ->
            val mark = p.design.blockOffsets[b]
            val scale = p.design.blocks[b].scale
            for (q in 0 until rank) da[mark * rank + q] = da[mark * rank + q] + base[q]
            for (c in 0 until hd) for (q in 0 until rank) da[(mark + 1 + c) * rank + q] = da[(mark + 1 + c) * rank + q] + scale * dSum[c * rank + q]
        }
    }

    /** The pair's own features added to the item's row of scores. */
    private fun addPair(i: Int, row: FloatArray, pair: DoubleArray) {
        p.examples[i].forEach { row[it] += pair[0].toFloat() }
        val (named, counts) = p.mentions[i]
        named.forEachIndexed { k, v -> row[v] += (pair[1] * counts[k]).toFloat() }
        val common = pair[2].toFloat()
        for (k in row.indices) row[k] += common
    }

    internal fun lossAndGradient(theta: DoubleArray, grad: DoubleArray, lambda: Double, trains: (Int) -> Boolean): Double {
        scores(theta)
        val pair = theta.copyOfRange(size - PAIR, size)
        val bias = size - PAIR - m
        // The total weight first: the loss is a mean, so the penalty means the same at any size of the task
        var total = 0.0
        val w = FloatArray(m)
        for (i in 0 until n) if (trains(i)) { p.weight.take(w, 0, i * m, m); for (k in 0 until m) total += w[k] }
        p.hints.forEach { total += 1 + it.against.size }
        if (total == 0.0) total = 1.0
        var loss = 0.0
        val row = FloatArray(m)
        val y = FloatArray(m)
        val gr = FloatArray(m)
        val gBias = DoubleArray(m)
        var gExample = 0.0
        var gMention = 0.0
        var gCommon = 0.0
        // Every value's own bias, and the scale of a slope: the loss of a row taken in the wide registers
        val biases = FloatArray(m) { theta[bias + it].toFloat() }
        val scale = (1 / total).toFloat()
        val rowSums = DoubleArray(2)
        for (i in 0 until n) {
            gr.fill(0f)
            if (trains(i)) {
                s.take(row, 0, i * m, m)
                p.weight.take(w, 0, i * m, m)
                p.label.take(y, 0, i * m, m)
                addPair(i, row, pair)
                rowSums.fill(0.0)
                Simd.weightedLogisticRow(row, biases, w, y, m, scale, gr, rowSums)
                loss += rowSums[0]
                gCommon += rowSums[1]
                for (k in 0 until m) gBias[k] += gr[k]
                p.examples[i].forEach { gExample += gr[it] }
                val (named, counts) = p.mentions[i]
                named.forEachIndexed { k, v -> gMention += gr[v] * counts[k] }
            }
            g.put(gr, 0, i * m, m)
        }
        // The names as texts that name their values
        if (h > 0) {
            val sums = DoubleArray(2)
            loss += hintLoss(theta, pair, total, gBias, sums)
            gMention += sums[0]
            gCommon += sums[1]
        }
        loss /= total
        // dU = G V, dV = Gᵀ U, dA = Xᵀ dU, dB = Zᵀ dV, dE = dV — the names' parts added
        Blas.product(g, m, n, m, v, rank, du)
        Blas.transposedProduct(g, m, n, m, u, rank, dv)
        if (h > 0) for (k in 0 until m * rank) if (dvHint[k] != 0f) dv[k] = dv[k] + dvHint[k]
        Blas.transposedProduct(p.x.held, dx, n, dx, du, rank, da)
        if (h > 0) hintGradient()
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

        /** The columns of the names' scores one core sums: a few cache lines of every row. */
        private const val COLUMNS = 64

        fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))
        fun softplus(z: Double) = if (z > 30) z else ln(1.0 + exp(z))
    }
}
