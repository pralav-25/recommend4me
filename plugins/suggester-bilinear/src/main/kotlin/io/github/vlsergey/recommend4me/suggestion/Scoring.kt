package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.ln1p

/**
 * THE SCORING OF THE ITEMS, APART FROM THE TASK — of every fold of the model ([BilinearSuggester.FOLDS]):
 * what carries an item's features to the [rank] coordinates ([a], itemWidth × rank, by the [design]),
 * the weights of a pair's own features ([pair]: the site gives the value as an example, the texts
 * name it, the common bias), and every value's coordinates ([vt], rank × m, transposed for the
 * product: its names and groups carried by B, its own coordinates added) and bias, of the values
 * [valueKeys] in their order.
 *
 * Of the item, its features alone: the chance an item has a value is σ(xᵢA · v + b_v + the pair's)
 * — a product of the item's coordinates with the value's kept. Made once of the task fitted on,
 * kept beside the model; a page of twenty works is scored by it of their own features, no name and
 * no group of any value read. Of a few megabytes: of a facet of 7 080 characters and 8 077 features
 * of an item, every fold's A 0.5 MB, its values 0.5 MB.
 */
internal class Scoring(
    val design: Design, val rank: Int, val valueKeys: List<String>,
    val a: List<FloatArray>, val pair: List<FloatArray>, val vt: List<FloatArray>, val bias: List<FloatArray>,
) {
    init {
        val m = valueKeys.size
        require(listOf(a, pair, vt, bias).all { it.size == BilinearSuggester.FOLDS }) { "Not a fold each" }
        require(vt.all { it.size == rank * m } && bias.all { it.size == m }) { "Not of the $m values" }
        require(a.all { it.size == design.itemWidth * rank }) { "Not of ${design.itemWidth} features" }
    }

    /**
     * The chances of every value of the items of [task] — of the values the scoring is of, in its
     * order. Every item by the model of the fold it was held out in, as fitted: a value the item has is
     * judged as if the item did not say so.
     */
    fun on(task: SuggestionTask): SuggestionScores {
        val m = valueKeys.size
        require(task.valueCount == m) { "${task.valueCount} values of a scoring of $m" }
        val sides = vt.map { Floats.of(it) }
        return SuggestionScores { rows ->
            val out = arrayOfNulls<FloatArray>(rows.size)
            val byFold = rows.indices.groupBy { Problem.foldOf(task.itemIds[rows[it]], BilinearSuggester.FOLDS) }
            byFold.forEach { (fold, at) ->
                val x = design.items(task, IntArray(at.size) { rows[at[it]] })
                val u = Floats(at.size * rank)
                Blas.product(x.held, design.itemWidth, at.size, design.itemWidth, Floats.of(a[fold]), rank, u)
                val s = Floats(at.size * m)
                Blas.product(u, rank, at.size, rank, sides[fold], m, s)
                val w = pair[fold]
                val b = bias[fold]
                at.forEachIndexed { r, k ->
                    val i = rows[k]
                    val row = FloatArray(m)
                    s.take(row, 0, r * m, m)
                    task.examples[i].forEach { row[it] += w[0] }
                    task.mentions[i]?.forEach { (v, count) -> if (v in 0 until m) row[v] += w[1] * ln1p(count.toDouble()).toFloat() }
                    for (j in 0 until m) row[j] = Bilinear.sigmoid(row[j] + b[j] + w[2])
                    out[k] = row
                }
            }
            out.map { it!! }
        }
    }

    /** Not compressed: numbers compress little, and a page waits for their reading. */
    fun pack(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes.buffered(BUFFER)).use { out ->
            out.writeInt(FORMAT)
            design.write(out)
            out.writeInt(rank)
            Design.writeStrings(out, valueKeys)
            for (f in a.indices) listOf(a[f], pair[f], vt[f], bias[f]).forEach { Design.writeFloats(out, it) }
        }
        return bytes.toByteArray()
    }

    companion object {
        /** The version of what [pack] writes. */
        private const val FORMAT = 1

        private const val BUFFER = 1 shl 16

        /** A scoring kept by [pack]; null of another version or of other bytes. */
        fun read(bytes: ByteArray): Scoring? = runCatching {
            DataInputStream(ByteArrayInputStream(bytes).buffered(BUFFER)).use { input ->
                if (input.readInt() != FORMAT) return null
                val design = Design.read(input)
                val rank = input.readInt()
                val keys = Design.readStrings(input)
                val parts = List(BilinearSuggester.FOLDS) { List(4) { Design.readFloats(input) } }
                Scoring(design, rank, keys, parts.map { it[0] }, parts.map { it[1] }, parts.map { it[2] }, parts.map { it[3] })
            }
        }.getOrNull()
    }
}
