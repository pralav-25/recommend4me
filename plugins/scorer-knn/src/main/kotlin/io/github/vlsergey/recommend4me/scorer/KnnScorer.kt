package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * THE k NEAREST RATED WORKS — a baseline: a work scores the mean grade of the [k][Knn.k] rated
 * works most alike it by the cosine of their rows of features, each weighed by its likeness. It
 * learns nothing; what it shows is how far the features alone, without a learnt weighing, tell
 * the works the user likes.
 *
 * The parameter is k; the most cautious — the most neighbours, the smoothest — first.
 */
class KnnScorer : Scorer {
    override val id = "knn-cosine"
    override val title = Label("Nearest rated works (kNN)", "Ближайшие оценённые (kNN)", "Najbliža ocenjena dela (kNN)")
    override val candidates = listOf(40.0, 20.0, 10.0, 5.0)
    override val defaultParameter = 10.0

    override fun fit(task: RankingTask, rows: IntArray, parameter: Double): FittedScorer {
        val d = task.x.cols
        val n = rows.size
        // The rated rows of unit length, transposed: d × n, so a product gives every cosine at once
        val columns = FloatArray(d * n)
        val row = FloatArray(d)
        rows.forEachIndexed { j, i ->
            task.x.held.take(row, 0, task.x.row(i), d)
            val norm = norm(row, d)
            for (q in 0 until d) columns[q * n + j] = row[q] / norm
        }
        return Knn(d, n, columns, FloatArray(n) { task.grades[rows[it]].toFloat() }, parameter.toInt().coerceAtLeast(1))
    }

    override fun unpack(bytes: ByteArray): FittedScorer {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val d = buffer.int
        val n = buffer.int
        val k = buffer.int
        val columns = FloatArray(d * n) { buffer.float }
        val grades = FloatArray(n) { buffer.float }
        return Knn(d, n, columns, grades, k)
    }

    /** The rated rows ([columns], d × n, of unit length) with their [grades]. */
    class Knn(private val d: Int, private val n: Int, private val columns: FloatArray, private val grades: FloatArray, val k: Int) : FittedScorer {

        private val held = Floats.of(columns)

        override fun scores(x: Matrix, rows: Int): FloatArray {
            check(x.cols == d) { "The neighbours have $d features, the rows ${x.cols}" }
            if (n == 0) return FloatArray(rows)
            val a = Floats(rows * d)
            val row = FloatArray(d)
            for (i in 0 until rows) {
                x.held.take(row, 0, x.row(i), d)
                val norm = norm(row, d)
                for (q in 0 until d) row[q] /= norm
                a.put(row, 0, i * d, d)
            }
            val cosines = Floats(rows * n)
            Blas.product(a, d, rows, d, held, n, cosines)
            val line = FloatArray(n)
            val nearest = minOf(k, n)
            return FloatArray(rows) { i ->
                cosines.take(line, 0, i * n, n)
                val order = line.indices.sortedByDescending { line[it] }.take(nearest)
                var sum = 0.0
                var weight = 0.0
                order.forEach { j ->
                    // A neighbour unlike the work still counts a little: the mean is never empty
                    val w = max(line[j].toDouble(), 0.0) + 1e-6
                    sum += w * grades[j]
                    weight += w
                }
                (sum / weight).toFloat()
            }
        }

        override fun pack(): ByteArray {
            val buffer = ByteBuffer.allocate(12 + 4 * (columns.size + grades.size)).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(d).putInt(n).putInt(k)
            columns.forEach { buffer.putFloat(it) }
            grades.forEach { buffer.putFloat(it) }
            return buffer.array()
        }
    }

    companion object {
        private fun norm(v: FloatArray, d: Int): Float {
            var s = 0.0
            for (q in 0 until d) s += v[q].toDouble() * v[q]
            return sqrt(s).toFloat().coerceAtLeast(1e-9f)
        }
    }
}
