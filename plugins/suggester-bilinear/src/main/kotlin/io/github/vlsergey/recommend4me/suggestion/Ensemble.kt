package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Simd
import java.util.concurrent.CancellationException
import kotlin.math.ln

/**
 * EVERY MODEL OF A FITTING IN ONE PRODUCT ([Bilinear], the same model and the same loss): the folds
 * and the penalties tried, side by side. Their weights A lie one under another, transposed — of a
 * model its rank's rows of the item's features — so the coordinates of every item by every model
 * are ONE product of the items' features, Uᵀ = Aᵀ Xᵀ, and the gradient back ONE more, dAᵀ = dUᵀ X:
 * the features of the items, a gigabyte and a half of a catalogue of games, read twice a step for
 * all the models, not twice for each.
 *
 * THE PAIRS OF THE ITEMS AND THEIR VALUES BY PRODUCTS TOO: a block of the items' rows against every
 * value, S = U Vᵀ, the slopes of the pairs that weigh anything written over it, and the gradient
 * back by two products, dU = S V and dV = Sᵀ U. The names as texts of a work ([Problem.hints]) the
 * same, a group of them at a time.
 *
 * ONE THREAD, THE LIBRARY SHARING EVERY PRODUCT OUT AMONG ITS OWN ([Blas.wide]): no thread of ours.
 * EVERY NUMBER WHERE THE PRODUCTS READ IT, made once for the fitting: nothing copied out and back
 * between them, no array made a step.
 *
 * The models step together, each by a search of its own ([Search]): a step works out the function
 * at the point every one of them asks for. A model whose search has ended leaves the product; the
 * last one takes its place, so the products are of the models still searching alone.
 */
internal class Ensemble(private val p: Problem, val rank: Int, private val lambdas: FloatArray, private val heldOut: IntArray) {
    private val n = p.n
    private val m = p.m
    private val dx = p.design.itemWidth
    private val dz = p.design.valueWidth
    private val r = rank

    /** How many models. */
    val count = lambdas.size

    /** The numbers of one model, in the order its search reads them: Aᵀ, Bᵀ, Eᵀ, the biases, the pair's own weights. */
    val size = r * dx + r * dz + r * m + m + Bilinear.PAIR

    // The point every model's search asks for, a piece of each kind one model after another — and the gradient there
    private val aT = Floats(count * r * dx)
    private val bT = Floats(count * r * dz)
    private val eT = Floats(count * r * m)
    private val bias = Floats(count * m)
    private val pair = Floats(count * Bilinear.PAIR)
    private val gA = Floats(count * r * dx)
    private val gB = Floats(count * r * dz)
    /** The gradient of E, which is dV: the values' coordinates' slopes, carried back to B from here. */
    private val gE = Floats(count * r * m)
    private val gBias = Floats(count * m)
    private val gPair = Floats(count * Bilinear.PAIR)

    /** The items' and the values' coordinates of every model, transposed: rank rows of every item, of every value. */
    private val uT = Floats(count * r * n)
    private val duT = Floats(count * r * n)
    private val vT = Floats(count * r * m)

    /** The rows of items a block of the pairs takes: a block of them against every value, held at once. */
    private val blockRows = minOf(n, maxOf(1, BLOCK / maxOf(1, m)))
    private val scores = Floats(blockRows * m)
    private val ones = Floats(maxOf(blockRows, p.hintGroups.maxOfOrNull { it.rows.size } ?: 0, 1)).also { o -> for (k in 0 until o.count) o[k] = 1f }

    // A row of the pairs that weigh anything, worked on in Java between the products: made once, as long as the longest row
    private val longest = p.active.maxOfOrNull { it.size } ?: 0
    private val z = FloatArray(longest)
    private val shift = FloatArray(maxOf(longest, p.hintGroups.maxOfOrNull { it.values.size } ?: 0))
    private val slopes = FloatArray(longest)
    private val sums = FloatArray(2)

    // The names as texts of a work: their coordinates of every model, and a group's names and values gathered
    private val h = p.hints.size
    private val hd = p.hintDim
    private val sumT = if (h > 0) Floats(count * r * hd) else Floats(0)
    private val uhT = if (h > 0) Floats(count * r * h) else Floats(0)
    private val duhT = if (h > 0) Floats(count * r * h) else Floats(0)
    private val dSumT = if (h > 0) Floats(count * r * hd) else Floats(0)
    private val largest = p.hintGroups.maxOfOrNull { it.rows.size } ?: 0
    private val widest = p.hintGroups.maxOfOrNull { it.values.size } ?: 0
    private val gu = Floats(r * largest)
    private val gv = Floats(r * widest)
    private val gs = Floats(largest * widest)
    private val gdu = Floats(r * largest)
    private val gdv = Floats(r * widest)
    private val columns = Floats(widest)
    private val hintBase = FloatArray(r)

    /** The total weight of every model's training: the loss a mean over it, the penalty the same at any size. */
    private val totals = FloatArray(count) { k ->
        var total = 0f
        for (i in 0 until n) if (p.foldOf[i] != heldOut[k]) total += p.rowWeight[i]
        p.hints.forEach { total += it.among.size }
        if (total == 0f) 1f else total
    }

    /** The model each slot of the products holds now: the models still searching, the first [active] slots. */
    private val slotModel = IntArray(count) { it }
    private val modelSlot = IntArray(count) { it }
    private var active = count

    /** Where the pieces of the model in [slot] lie, in the point or in the gradient. */
    private fun pieces(slot: Int, gradient: Boolean) = Pieces(
        if (gradient) arrayOf(gA, gB, gE, gBias, gPair) else arrayOf(aT, bT, eT, bias, pair),
        intArrayOf(slot * r * dx, slot * r * dz, slot * r * m, slot * m, slot * Bilinear.PAIR),
        intArrayOf(r * dx, r * dz, r * m, m, Bilinear.PAIR),
    )

    /** Of every slot, its pieces of the point and of the gradient: told once. */
    private val points = Array(count) { pieces(it, gradient = false) }
    private val grads = Array(count) { pieces(it, gradient = true) }

    /** What a fitting of every model gives: the weights of each, in [Bilinear]'s layout, how many losses each took, when each ended. */
    class Fits(val weights: List<FloatArray>, val losses: IntArray, val endedAt: LongArray)

    /** Every model fitted from its start ([starts], each of [Bilinear]'s layout), [maxSteps] steps at most. */
    fun fit(starts: List<FloatArray>, maxSteps: Int, stop: () -> Boolean): Fits = Blas.wide {
        val searches = Array(count) { k -> Search(size, maxSteps).also { it.begin(ordered(starts[k])) } }
        val losses = FloatArray(count)
        val endedAt = LongArray(count)
        while (active > 0) {
            if (stop()) throw CancellationException("the search is given up")
            for (slot in 0 until active) searches[slotModel[slot]].propose(points[slot])
            evaluate(losses)
            for (slot in 0 until active) searches[slotModel[slot]].answer(losses[slot], points[slot], grads[slot])
            // A model whose search ended leaves the products: the last one still searching takes its slot
            var slot = 0
            while (slot < active) {
                val model = slotModel[slot]
                if (searches[model].done) {
                    endedAt[model] = System.currentTimeMillis()
                    leave(slot)
                } else slot++
            }
        }
        Fits(searches.map { standard(it.x) }, IntArray(count) { searches[it].evaluations }, endedAt)
    }

    /**
     * The model in [slot] leaves the products, the last of those still searching moved into its
     * place — nothing of it copied: its search writes its whole point there at the next step.
     */
    private fun leave(slot: Int) {
        val last = active - 1
        if (slot != last) {
            val moved = slotModel[last]
            val gone = slotModel[slot]
            slotModel[slot] = moved
            modelSlot[moved] = slot
            slotModel[last] = gone
            modelSlot[gone] = last
        }
        active--
    }

    /**
     * The loss of the model in every slot still searching at its point, into [losses]; its gradient
     * into the gradient's pieces. [Bilinear.lossAndGradient] of every model at once.
     */
    internal fun evaluate(losses: FloatArray) {
        val k = active
        val kr = k * r
        // Uᵀ = Aᵀ Xᵀ and Vᵀ = Bᵀ Zᵀ + Eᵀ of every model, one product each
        Blas.gemm(false, true, kr, n, dx, aT, 0, dx, p.x.held, 0, dx, uT, 0, n)
        Blas.gemm(false, true, kr, m, dz, bT, 0, dz, p.z.held, 0, dz, vT, 0, m)
        Simd.axpy(1f, eT, 0, vT, 0, kr * m)
        if (h > 0) namesCoordinates(k)
        val named = ln(2.0).toFloat()
        for (slot in 0 until k) {
            val model = slotModel[slot]
            val scale = 1 / totals[model]
            val common = pair[slot * Bilinear.PAIR + 2]
            var loss = pairs(slot, model, scale, common)
            if (h > 0) loss += names(slot, scale, common, named)
            losses[slot] = loss * scale
        }
        // dBᵀ = dVᵀ Z and dAᵀ = dUᵀ X of every model, one product each; the names' part added to dA after
        Blas.gemm(false, false, kr, dz, m, gE, 0, m, p.z.held, 0, dz, gB, 0, dz)
        Blas.gemm(false, false, kr, dx, n, duT, 0, n, p.x.held, 0, dx, gA, 0, dx)
        if (h > 0) namesGradient(k)
        // The penalty on every weight, once the products have written the gradient: the pair's own
        // too, but the common bias, the base rate of every value
        for (slot in 0 until k) {
            val lambda = lambdas[slotModel[slot]]
            val common = pair[slot * Bilinear.PAIR + 2]
            val theta = points[slot]
            val gradient = grads[slot]
            var penalty = 0f
            for (q in theta.floats.indices) {
                penalty += Simd.dot(theta.floats[q], theta.at[q], theta.floats[q], theta.at[q], theta.count[q])
                Simd.axpy(2 * lambda, theta.floats[q], theta.at[q], gradient.floats[q], gradient.at[q], theta.count[q])
            }
            gPair[slot * Bilinear.PAIR + 2] = gPair[slot * Bilinear.PAIR + 2] - 2 * lambda * common
            losses[slot] += lambda * (penalty - common * common)
        }
    }

    /**
     * The pairs of the items and their values of the model in [slot], a block of rows at a time:
     * the scores of every pair, S = U Vᵀ; of those that weigh anything the loss and the slope, written
     * over S, the rest nought; dU = S V, dV = Sᵀ U, the slopes of the biases its columns summed. The
     * loss summed, not averaged; the slopes of the pair's own weights into the gradient.
     */
    private fun pairs(slot: Int, model: Int, scale: Float, common: Float): Float {
        val uAt = slot * r * n
        val vAt = slot * r * m
        val biasAt = slot * m
        val pairAt = slot * Bilinear.PAIR
        val example = pair[pairAt]
        val mention = pair[pairAt + 1]
        var loss = 0f
        var gCommon = 0f
        var gExample = 0f
        var gMention = 0f
        var from = 0
        while (from < n) {
            val rows = minOf(blockRows, n - from)
            // S = U Vᵀ of the block's rows: Uᵀ's columns of them against Vᵀ
            Blas.gemm(true, false, rows, m, r, uT, uAt + from, n, vT, vAt, m, scores, 0, m)
            for (b in 0 until rows) {
                val i = from + b
                val at = p.active[i]
                val row = b * m
                if (p.foldOf[i] == heldOut[model] || at.isEmpty()) {
                    scores.clear(row, m)
                    continue
                }
                val len = at.size
                for (j in 0 until len) {
                    z[j] = scores[row + at[j]]
                    shift[j] = bias[biasAt + at[j]] + common
                }
                p.activeExamples[i].forEach { z[it] += example }
                val named = p.activeNamed[i]
                val counts = p.activeCounts[i]
                for (q in named.indices) z[named[q]] += mention * counts[q]
                sums[0] = 0f
                sums[1] = 0f
                Simd.weightedLogisticRow(z, shift, p.activeWeight[i], p.activeLabel[i], len, scale, slopes, sums)
                loss += sums[0]
                gCommon += sums[1]
                p.activeExamples[i].forEach { gExample += slopes[it] }
                for (q in named.indices) gMention += slopes[named[q]] * counts[q]
                scores.clear(row, m)
                for (j in 0 until len) scores[row + at[j]] = slopes[j]
            }
            // dUᵀ of the block's rows = Vᵀ Sᵀ; dVᵀ += Uᵀ of them · S; the biases' slopes += Sᵀ 1
            Blas.gemm(false, true, r, rows, m, vT, vAt, m, scores, 0, m, duT, uAt + from, n)
            val first = if (from == 0) 0f else 1f
            Blas.gemm(false, false, r, m, rows, uT, uAt + from, n, scores, 0, m, gE, vAt, m, first)
            Blas.gemv(true, rows, m, scores, 0, m, ones, 0, gBias, biasAt, first)
            from += rows
        }
        gPair[pairAt] = gExample
        gPair[pairAt + 1] = gMention
        gPair[pairAt + 2] = gCommon
        return loss
    }

    /**
     * The names' coordinates of every model: Uhᵀ = (Σ_b s_b A_bᵀ) Hᵀ + A_constᵀ + Σ_b A_mark(b)ᵀ —
     * every text block of theirs holds the name, so the text blocks' weights summed meet it once.
     */
    private fun namesCoordinates(k: Int) {
        sumT.clear(0, k * r * hd)
        for (slot in 0 until k) for (q in 0 until r) {
            val rowA = (slot * r + q) * dx
            val rowSum = (slot * r + q) * hd
            p.hintBlocks.forEach { b ->
                Simd.axpy(p.design.blocks[b].scale, aT, rowA + p.design.blockOffsets[b] + 1, sumT, rowSum, hd)
            }
        }
        Blas.gemm(false, true, k * r, h, hd, sumT, 0, hd, p.hintText.held, 0, hd, uhT, 0, h)
        for (slot in 0 until k) for (q in 0 until r) {
            val rowA = (slot * r + q) * dx
            var base = aT[rowA]
            p.hintBlocks.forEach { b -> base += aT[rowA + p.design.blockOffsets[b]] }
            Simd.add(base, uhT, (slot * r + q) * h, h)
        }
        duhT.clear(0, k * r * h)
    }

    /**
     * The loss of every name of the model in [slot] read as a text that names its value and none of
     * its rivals, weight 1 a pair, a group of them at a time: the group's names' and values'
     * coordinates gathered, their scores by one product, the slopes back by two; the loss summed.
     */
    private fun names(slot: Int, scale: Float, common: Float, named: Float): Float {
        val uhAt = slot * r * h
        val vAt = slot * r * m
        val biasAt = slot * m
        val pairAt = slot * Bilinear.PAIR
        val mention = pair[pairAt + 1]
        var loss = 0f
        var gCommon = 0f
        var gMention = 0f
        p.hintGroups.forEach { group ->
            val kRows = group.rows.size
            val c = group.values.size
            for (q in 0 until r) {
                for (row in 0 until kRows) gu[q * kRows + row] = uhT[uhAt + q * h + group.rows[row]]
                for (j in 0 until c) gv[q * c + j] = vT[vAt + q * m + group.values[j]]
            }
            for (j in 0 until c) shift[j] = bias[biasAt + group.values[j]] + common
            // The scores of the group's every name against its every value
            Blas.gemm(true, false, kRows, c, r, gu, 0, kRows, gv, 0, c, gs, 0, c)
            // Every value of a row against the label nought at once; the name's own value then set
            // right: its label is one, and the text names it
            for (row in 0 until kRows) {
                val own = group.positive[row]
                val at = row * c
                val before = if (own in 0 until c) gs[at + own] + shift[own] else 0f
                sums[0] = 0f
                sums[1] = 0f
                Simd.logisticRow(gs, at, shift, c, scale, sums)
                if (own in 0 until c) {
                    val zOwn = before + mention * named
                    val d = (Bilinear.sigmoid(zOwn) - 1) * scale
                    sums[0] += Bilinear.softplus(zOwn) - zOwn - Bilinear.softplus(before)
                    sums[1] += d - Bilinear.sigmoid(before) * scale
                    gs[at + own] = d
                    gMention += d * named
                }
                loss += sums[0]
                gCommon += sums[1]
            }
            // The slopes of the values' biases: the columns summed
            Blas.gemv(true, kRows, c, gs, 0, c, ones, 0, columns, 0)
            for (j in 0 until c) gBias[biasAt + group.values[j]] = gBias[biasAt + group.values[j]] + columns[j]
            // The gradient of the group's coordinates, scattered back: of the names dUhᵀ = Vᵀ Sᵀ, of the values dVᵀ = Uhᵀ S
            Blas.gemm(false, true, r, kRows, c, gv, 0, c, gs, 0, c, gdu, 0, kRows)
            Blas.gemm(false, false, r, c, kRows, gu, 0, kRows, gs, 0, c, gdv, 0, c)
            for (q in 0 until r) {
                for (row in 0 until kRows) {
                    val at = uhAt + q * h + group.rows[row]
                    duhT[at] = duhT[at] + gdu[q * kRows + row]
                }
                for (j in 0 until c) {
                    val at = vAt + q * m + group.values[j]
                    gE[at] = gE[at] + gdv[q * c + j]
                }
            }
        }
        gPair[pairAt + 1] = gPair[pairAt + 1] + gMention
        gPair[pairAt + 2] = gPair[pairAt + 2] + gCommon
        return loss
    }

    /** The names' slopes carried back to the text blocks' weights, the constant's and the marks': of every model, one product. */
    private fun namesGradient(k: Int) {
        Blas.gemm(false, false, k * r, hd, h, duhT, 0, h, p.hintText.held, 0, hd, dSumT, 0, hd)
        for (slot in 0 until k) for (q in 0 until r) {
            val rowA = (slot * r + q) * dx
            val rowSum = (slot * r + q) * hd
            val base = Simd.sum(duhT, (slot * r + q) * h, h)
            gA[rowA] = gA[rowA] + base
            p.hintBlocks.forEach { b ->
                val mark = rowA + p.design.blockOffsets[b]
                gA[mark] = gA[mark] + base
                Simd.axpy(p.design.blocks[b].scale, dSumT, rowSum, gA, mark + 1, hd)
            }
        }
    }

    /**
     * The held-out loss of every model, the pairs of the items of its fold that weigh anything, as
     * [Bilinear.heldOutLoss] takes them — of the weights [fitted] ([Bilinear]'s layout), into [into]
     * and, of the values no item outside the fold has, [unseen].
     */
    fun heldOut(fitted: List<FloatArray>, into: List<Bilinear.Held>, unseen: List<Bilinear.Held>) = Blas.wide {
        active = count
        for (model in 0 until count) {
            slotModel[model] = model
            modelSlot[model] = model
            points[model].write(Floats.of(ordered(fitted[model])))
        }
        val kr = count * r
        Blas.gemm(false, true, kr, n, dx, aT, 0, dx, p.x.held, 0, dx, uT, 0, n)
        Blas.gemm(false, true, kr, m, dz, bT, 0, dz, p.z.held, 0, dz, vT, 0, m)
        Simd.axpy(1f, eT, 0, vT, 0, kr * m)
        for (model in 0 until count) {
            val fold = heldOut[model]
            val fresh = p.unseenOutside(fold)
            val uAt = model * r * n
            val vAt = model * r * m
            val example = pair[model * Bilinear.PAIR]
            val mention = pair[model * Bilinear.PAIR + 1]
            val common = pair[model * Bilinear.PAIR + 2]
            for (i in 0 until n) {
                if (p.foldOf[i] != fold) continue
                val at = p.active[i]
                for (j in at.indices) {
                    var dot = bias[model * m + at[j]] + common
                    for (q in 0 until r) dot += uT[uAt + q * n + i] * vT[vAt + q * m + at[j]]
                    z[j] = dot
                }
                p.activeExamples[i].forEach { z[it] += example }
                p.activeNamed[i].forEachIndexed { q, j -> z[j] += mention * p.activeCounts[i][q] }
                val w = p.activeWeight[i]
                val y = p.activeLabel[i]
                for (j in at.indices) {
                    val l = (Bilinear.softplus(z[j]) - y[j] * z[j]).toDouble()
                    into[model].add(w[j].toDouble(), l)
                    if (fresh[at[j]]) unseen[model].add(w[j].toDouble(), l)
                }
            }
        }
    }

    /**
     * The loss and the gradient of every model at [thetas] ([Bilinear]'s layout) in one step of the
     * products: what [Bilinear.lossAndGradient] gives each — the measure this is checked against.
     */
    internal fun lossesAndGradients(thetas: List<FloatArray>): Pair<FloatArray, List<FloatArray>> = Blas.wide {
        active = count
        for (model in 0 until count) {
            slotModel[model] = model
            modelSlot[model] = model
            points[model].write(Floats.of(ordered(thetas[model])))
        }
        val losses = FloatArray(count)
        evaluate(losses)
        val own = Floats(size)
        losses to List(count) { model ->
            grads[model].read(own)
            standard(own)
        }
    }

    /** Weights of [Bilinear]'s layout — A (dx × rank), B (dz × rank), E (m × rank), biases, the pair's — in this one's: each transposed. */
    private fun ordered(theta: FloatArray): FloatArray {
        val out = FloatArray(size)
        transpose(theta, 0, dx, out, 0)
        transpose(theta, dx * r, dz, out, r * dx)
        transpose(theta, dx * r + dz * r, m, out, r * dx + r * dz)
        theta.copyInto(out, r * dx + r * dz + r * m, dx * r + dz * r + m * r, theta.size)
        return out
    }

    /** This one's layout back in [Bilinear]'s. */
    private fun standard(x: Floats): FloatArray {
        val own = x.toArray()
        val out = FloatArray(size)
        untranspose(own, 0, dx, out, 0)
        untranspose(own, r * dx, dz, out, dx * r)
        untranspose(own, r * dx + r * dz, m, out, dx * r + dz * r)
        own.copyInto(out, dx * r + dz * r + m * r, r * dx + r * dz + r * m, own.size)
        return out
    }

    /** rows × rank at [from] of [src] written rank × rows at [to] of [out]. */
    private fun transpose(src: FloatArray, from: Int, rows: Int, out: FloatArray, to: Int) {
        for (f in 0 until rows) for (q in 0 until r) out[to + q * rows + f] = src[from + f * r + q]
    }

    private fun untranspose(src: FloatArray, from: Int, rows: Int, out: FloatArray, to: Int) {
        for (f in 0 until rows) for (q in 0 until r) out[to + f * r + q] = src[from + q * rows + f]
    }

    companion object {
        /** The scores of the pairs held at once, a block of the items' rows against every value: memory, not meaning. */
        const val BLOCK = 8 shl 20
    }
}
