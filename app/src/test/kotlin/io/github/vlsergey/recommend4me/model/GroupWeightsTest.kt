package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.scorer.FittedScorer
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.scorer.Scorer
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupWeightsTest {

    /** Every column weighed by how it goes with the grades on the rows learnt from: a wide group of noise drowns a column of signal. */
    private class CovarianceScorer : Scorer {
        override val id = "covariance"
        override val candidates = listOf(1.0)
        override val defaultParameter = 1.0

        override fun fit(task: RankingTask, rows: IntArray, parameter: Double): FittedScorer {
            val mean = rows.map { task.grades[it] }.average()
            val w = FloatArray(task.x.cols) { c -> rows.sumOf { r -> (task.grades[r] - mean) * task.x.held[task.x.row(r) + c] }.toFloat() }
            return object : FittedScorer {
                override fun scores(x: Matrix, rows: Int) = FloatArray(rows) { r -> (0 until x.cols).sumOf { c -> (w[c] * x.held[x.row(r) + c]).toDouble() }.toFloat() }
                override fun pack() = ByteArray(0)
            }
        }

        override fun unpack(bytes: ByteArray): FittedScorer = error("not kept")
    }

    /** Works graded 1 to 5; a column that tells the grade, and two hundred of noise. */
    private fun task(): RankingTask {
        val random = Random(7)
        val n = 80
        val noise = 200
        val grades = IntArray(n) { 1 + it % 5 }
        val x = Matrix(n, noise + 1)
        for (r in 0 until n) {
            for (c in 0 until noise) x.held[x.row(r) + c] = random.nextDouble(-1.0, 1.0).toFloat() * 3
            x.held[x.row(r) + noise] = (grades[r] + random.nextDouble(-1.0, 1.0)).toFloat()
        }
        return RankingTask(x, grades, LongArray(n) { it.toLong() }, LongArray(n) { it.toLong() })
    }

    private val groups = GroupWeights.groups(mapOf("noise" to (0 until 200).toList(), "signal" to listOf(200)))

    @Test
    fun `a wide group of noise is weighed down, and the ranking of works the choice never saw is better for it`() {
        assertEquals(listOf("noise", GroupWeights.REST), groups.map { it.part })
        val task = task()
        val tuned = GroupWeights.tune(task, null, CovarianceScorer(), groups)
        assertTrue(tuned.weights[0] < 1.0, "the noise weighed ${tuned.weights.toList()}")
        val folds = GroupWeights.nested(task, null, CovarianceScorer(), groups)
        assertTrue(folds.isNotEmpty())
        val (gain, _) = GroupWeights.meanAndError(folds.map { it.weighed - it.baseline })
        assertTrue(gain > 0, "the folds ${folds.map { it.baseline to it.weighed }}")
    }

    @Test
    fun `every weight 1 leaves the rows as they are`() {
        val task = task()
        assertTrue(GroupWeights.scaled(task.x, groups, doubleArrayOf(1.0, 1.0)) === task.x)
        val halved = GroupWeights.scaled(task.x, groups, doubleArrayOf(0.5, 1.0))
        assertEquals(task.x.held[0] * 0.5f, halved.held[0])
        assertEquals(task.x.held[200], halved.held[200])
    }
}
