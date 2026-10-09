package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
 */
class BilinearSuggester : FacetSuggester {
    override val id = "bilinear-1"

    override fun fit(task: SuggestionTask): FittedSuggester? {
        if (task.valueCount == 0 || task.assigned.all { it.isEmpty() }) return null
        val started = System.currentTimeMillis()
        val design = Design.of(task)
        val problem = Problem(task, design, FOLDS)
        class Tried(val rank: Int, val lambda: Double, val held: Bilinear.Held, val fitted: Fitted)
        val tried = ArrayList<Tried>()
        for (rank in RANKS) {
            val model = Bilinear(problem, rank)
            // From the strongest penalty down by tenfold steps, while the held-out loss still falls
            var worse = 0
            var rankBest = Double.MAX_VALUE
            var lambda = STRONGEST
            while (lambda >= WEAKEST && worse < 2) {
                val held = Bilinear.Held()
                // Every penalty from a fresh start: carried from a stronger one, the weights would start
                // at nought, where the two sides' products have no gradient and stay
                val thetas = Array(FOLDS) { k -> model.fit(model.start(k.toLong() + 1), lambda, k) }
                thetas.forEachIndexed { k, theta -> model.heldOutLoss(theta, k, held) }
                tried += Tried(rank, lambda, held, Fitted(design, rank, lambda, thetas.map { model.weights(it) }))
                if (held.mean < rankBest) { rankBest = held.mean; worse = 0 } else worse++
                lambda /= 10
            }
        }
        // The one-standard-error rule (Breiman et al.): of the penalties that hold out no worse than
        // the best by more than its standard error, the strongest — the data cannot tell them apart,
        // and the stronger guesses less where an item is unlike those it learnt from
        val least = tried.minBy { it.held.mean }
        val chosen = tried.filter { it.held.mean <= least.held.mean + least.held.error }.maxBy { it.lambda }
        log.info(
            "{} items × {} values, {} features of an item, {} of a value: {}; chosen r={} λ={} — in {} ms",
            task.itemCount, task.valueCount, design.itemWidth, design.valueWidth,
            tried.joinToString("; ") { "r=${it.rank} λ=${it.lambda}: %.4f ± %.4f".format(it.held.mean, it.held.error) },
            chosen.rank, chosen.lambda, System.currentTimeMillis() - started,
        )
        return chosen.fitted
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? = runCatching {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
            if (input.readInt() != FORMAT) return null
            val design = Design.read(input)
            val rank = input.readInt()
            val lambda = input.readDouble()
            val weights = List(input.readInt()) { Weights.read(input) }
            if (weights.size != FOLDS || !design.reads(task)) null else Fitted(design, rank, lambda, weights)
        }
    }.getOrNull()

    /** The model of every fold, fitted with the [rank] and the [lambda] chosen, on the [design] of the task fitted on. */
    private class Fitted(val design: Design, val rank: Int, val lambda: Double, val folds: List<Weights>) : FittedSuggester {

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
                        for (j in 0 until m) row[j] = Bilinear.sigmoid((row[j] + bias[j] + w.pair[2]).toDouble()).toFloat()
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
            }
            return bytes.toByteArray()
        }
    }

    companion object {
        private const val FORMAT = 2

        /** The folds an item is held out in: four fifths of the items teach the model judging the fifth. */
        const val FOLDS = 5

        /** The ranks tried: how many coordinates the items and the values meet in. */
        val RANKS = listOf(16)

        /**
         * The penalties tried: from [STRONGEST] — at which every weight is all but nought — down
         * tenfold at a time to [WEAKEST], stopping after two that held out worse than the best.
         */
        const val STRONGEST = 10.0
        const val WEAKEST = 1e-7
    }
}
