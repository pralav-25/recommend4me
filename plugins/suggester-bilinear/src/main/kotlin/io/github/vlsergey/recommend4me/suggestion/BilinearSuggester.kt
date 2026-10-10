package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.stream.IntStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ln

private val log = LoggerFactory.getLogger(BilinearSuggester::class.java)

/**
 * THE VALUES OF A FACET BY ONE MODEL ([Bilinear]) of every item and every value — the tags, the
 * universes, the characters, the pairings alike: every input of the item the model of the grades
 * reads, the item's other facets, the value's name and groups, the pair's own features.
 *
 * EVERY ITEM IS JUDGED BY A MODEL THAT DID NOT LEARN FROM IT. The items fall into [FOLDS] folds by
 * their ids; a model is fitted on the items of all folds but one, and judges the items of that one.
 * What the site or the user said of a work does not vouch for itself: a value the site gave it that
 * nothing else supports comes out unlikely, and an answer on a work does not move its own chances —
 * the next likeliest are offered after it.
 *
 * NO NUMBER OF THE MODEL IS CHOSEN BY HAND: the rank and the penalty are chosen among [RANKS] and
 * the penalties from [STRONGEST] down by the held-out loss of every fold summed — the strongest
 * penalty within one standard error of the least.
 *
 * A REFIT GOES ON FROM THE STATE FITTED LAST: its weights carried to the task as it is now, by the
 * keys of the features and the values, and the penalty chosen again beside the last one — seconds
 * where a search from scratch takes minutes. From scratch only the first time, or when the last
 * state does not read the task.
 */
class BilinearSuggester internal constructor(
    /** The names' loss on the card, when there is one: on the processor without. */
    private val card: NamesOnCard?,
) : FacetSuggester {
    constructor() : this(null)

    override val id = "bilinear-1"

    override fun fit(task: SuggestionTask, previous: FittedSuggester?): FittedSuggester? {
        if (task.valueCount == 0 || task.assigned.all { it.isEmpty() }) return null
        val started = System.currentTimeMillis()
        val design = Design.of(task)
        val problem = Problem(task, design, FOLDS)
        class Tried(val rank: Int, val lambda: Double, val held: Bilinear.Held, val unseen: Bilinear.Held, val fitted: Fitted, val losses: List<Int>, val millis: Long)
        val tried = ArrayList<Tried>()
        // Every penalty from the strongest down by tenfold steps, to the weakest: the held-out loss is
        // not a valley — the characters' was flat from 10 to 0.1, worse at 0.01 and least at 1e-4 —
        // and a search stopped at the first rise never reaches the better side of it
        val grid = generateSequence(STRONGEST) { it / 10 }.takeWhile { it >= WEAKEST }.toList()
        // GONE ON FROM THE STATE FITTED LAST: a few answers or works more move the optimum a little,
        // and the walk to it from the last is a few steps, where a search from scratch is hundreds. The
        // penalty is then chosen again among the last one and the two beside it on the grid — the
        // choice moves with the data, a step at a time
        val last = (previous as? Fitted)?.takeIf { it.folds.size == FOLDS && it.rank in RANKS }
        val ranks = if (last == null) RANKS else listOf(last.rank)
        val lambdas = if (last == null) grid else grid.indices.minBy { abs(ln(grid[it] / last.lambda)) }.let { at ->
            grid.subList(maxOf(0, at - 1), minOf(grid.size, at + 2))
        }
        for (rank in ranks) {
            // A model of every fold, fitted side by side: the folds share nothing but the problem
            val models = Array(FOLDS) { Bilinear(problem, rank, card) }
            for (lambda in lambdas) {
                val held = Bilinear.Held()
                val unseen = Bilinear.Held()
                // Every penalty from its own last weights carried to this task — those of the penalty
                // nearest to it when it had none — or from a fresh start: from scratch, or when the
                // last weights are all nought, where the two sides' products have no gradient and stay.
                // From scratch every penalty from a fresh start: carried from a stronger one, the
                // weights would start at nought
                val own = last?.nearest(lambda)
                val starts = Array(FOLDS) { k ->
                    own?.let { models[k].carried(it[k], last.design) }?.takeIf { theta -> theta.any { it != 0f } } ?: models[k].start(k.toLong() + 1)
                }
                val step = lambda.toFloat()
                val before = models.map { it.losses }
                val begun = System.currentTimeMillis()
                val thetas = IntStream.range(0, FOLDS).parallel()
                    .mapToObj { k -> models[k].fit(starts[k], step, k) }.toList()
                thetas.forEachIndexed { k, theta -> models[k].heldOutLoss(theta, k, held, unseen) }
                tried += Tried(
                    rank, lambda, held, unseen, Fitted(design, rank, lambda, thetas.mapIndexed { k, theta -> models[k].weights(theta) }),
                    models.indices.map { models[it].losses - before[it] }, System.currentTimeMillis() - begun,
                )
            }
        }
        // The one-standard-error rule (Breiman et al.): of the penalties that hold out no worse than
        // the best by more than its standard error, the strongest — the data cannot tell them apart,
        // and the stronger guesses less where an item is unlike those it learnt from
        val least = tried.minBy { it.held.mean }
        val chosen = tried.filter { it.held.mean <= least.held.mean + least.held.error }.maxBy { it.lambda }
        log.info(
            "{} items × {} values, {} features of an item, {} of a value, {} names against {} values, {}: {}; chosen r={} λ={} — in {} ms; " +
                "of the values no item of the other folds has: {}",
            task.itemCount, task.valueCount, design.itemWidth, design.valueWidth, problem.hints.size, problem.hintPairs,
            if (last == null) "from scratch" else "from the last state",
            tried.joinToString("; ") { "r=${it.rank} λ=${it.lambda}: %.4f ± %.4f (losses %s in %d ms)".format(it.held.mean, it.held.error, it.losses, it.millis) },
            chosen.rank, chosen.lambda, System.currentTimeMillis() - started,
            tried.joinToString("; ") { "λ=${it.lambda}: " + if (it.unseen.weight > 0) "%.4f ± %.4f".format(it.unseen.mean, it.unseen.error) else "none" },
        )
        // Kept with the chosen weights: those of the penalties beside it, each of the next refit's to go on from
        val at = grid.indices.minBy { abs(ln(grid[it] / chosen.lambda)) }
        val beside = setOfNotNull(grid.getOrNull(at - 1), grid.getOrNull(at + 1))
        val nearby = tried.filter { it.rank == chosen.rank && it.lambda in beside }.associate { it.lambda to it.fitted.folds }
        return Fitted(design, chosen.rank, chosen.lambda, chosen.fitted.folds, nearby)
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? = runCatching {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
            // Of the format before it, the same without the penalties beside the chosen one
            val format = input.readInt()
            if (format != FORMAT && format != FORMAT - 1) return null
            val design = Design.read(input)
            val rank = input.readInt()
            val lambda = input.readDouble()
            val weights = List(input.readInt()) { Weights.read(input) }
            val nearby = if (format < FORMAT) emptyMap() else List(input.readInt()) { input.readDouble() to List(input.readInt()) { Weights.read(input) } }.toMap()
            if (weights.size != FOLDS || !design.reads(task)) null else Fitted(design, rank, lambda, weights, nearby)
        }
    }.getOrNull()

    /**
     * The model of every fold, fitted with the [rank] and the [lambda] chosen, on the [design] of the
     * task fitted on; and the folds' weights at the penalties beside it ([nearby]), for a refit to go on from.
     */
    private class Fitted(
        val design: Design, val rank: Int, val lambda: Double, val folds: List<Weights>, val nearby: Map<Double, List<Weights>> = emptyMap(),
    ) : FittedSuggester {

        /** The folds' weights at the penalty kept nearest to [penalty]. */
        fun nearest(penalty: Double): List<Weights> =
            (nearby + (lambda to folds)).minBy { abs(ln(it.key / penalty)) }.value.takeIf { it.size == FOLDS } ?: folds

        override fun on(task: SuggestionTask): SuggestionScores {
            val m = task.valueCount
            val z = design.values(task)
            val known = design.valueIndices(task)
            val dz = design.valueWidth
            // Every fold's coordinates of the task's values, transposed for the product, and its biases
            val sides = folds.map { w ->
                val b = Floats.of(w.b)
                val v = Floats(m * rank)
                Blas.product(z.held, dz, m, dz, b, rank, v)
                val vt = Floats(rank * m)
                val bias = FloatArray(m)
                for (j in 0 until m) {
                    val own = known[j]
                    for (q in 0 until rank) vt[q * m + j] = v[j * rank + q] + (if (own >= 0) w.e[own * rank + q] else 0f)
                    if (own >= 0) bias[j] = w.bias[own]
                }
                vt to bias
            }
            return SuggestionScores { rows ->
                val out = arrayOfNulls<FloatArray>(rows.size)
                val byFold = rows.indices.groupBy { Problem.foldOf(task.itemIds[rows[it]], FOLDS) }
                byFold.forEach { (fold, at) ->
                    val w = folds[fold]
                    val x = design.items(task, IntArray(at.size) { rows[at[it]] })
                    val u = Floats(at.size * rank)
                    Blas.product(x.held, design.itemWidth, at.size, design.itemWidth, Floats.of(w.a), rank, u)
                    val (vt, bias) = sides[fold]
                    val s = Floats(at.size * m)
                    Blas.product(u, rank, at.size, rank, vt, m, s)
                    at.forEachIndexed { r, k ->
                        val i = rows[k]
                        val row = FloatArray(m)
                        s.take(row, 0, r * m, m)
                        task.examples[i].forEach { row[it] += w.pair[0] }
                        task.mentions[i]?.forEach { (v, count) -> if (v in 0 until m) row[v] += w.pair[1] * kotlin.math.ln1p(count.toDouble()).toFloat() }
                        for (j in 0 until m) row[j] = Bilinear.sigmoid(row[j] + bias[j] + w.pair[2])
                        out[k] = row
                    }
                }
                out.map { it!! }
            }
        }

        override fun pack(): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(bytes)).use { out ->
                out.writeInt(FORMAT)
                design.write(out)
                out.writeInt(rank)
                out.writeDouble(lambda)
                out.writeInt(folds.size)
                folds.forEach { it.write(out) }
                out.writeInt(nearby.size)
                nearby.forEach { (penalty, weights) ->
                    out.writeDouble(penalty)
                    out.writeInt(weights.size)
                    weights.forEach { it.write(out) }
                }
            }
            return bytes.toByteArray()
        }
    }

    companion object {
        /**
         * The version of what [pack] writes; 5: fitted in single precision, every facet fitted again;
         * 6: the weights at the penalties beside the chosen one too — a state of 5 is read as one without.
         */
        private const val FORMAT = 6

        /** The folds an item is held out in: four fifths of the items teach the model judging the fifth. */
        const val FOLDS = 5

        /** The ranks tried: how many coordinates the items and the values meet in. */
        val RANKS = listOf(16)

        /**
         * The penalties tried: from [STRONGEST] — at which every weight is all but nought — down
         * tenfold at a time to [WEAKEST], every one of them when fitted from scratch; the last
         * one chosen and the two beside it when gone on from the state fitted last.
         */
        const val STRONGEST = 10.0
        const val WEAKEST = 1e-7
    }
}
