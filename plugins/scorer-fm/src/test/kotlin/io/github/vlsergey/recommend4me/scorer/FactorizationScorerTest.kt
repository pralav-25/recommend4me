package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FactorizationScorerTest {

    @Test
    fun `the gradient is the loss's`() {
        val random = Random(3)
        val n = 12
        val d = 5
        val x = Matrix(n, d)
        for (k in 0 until n * d) x.held[k] = random.nextFloat() * 2 - 1
        val grades = IntArray(n) { 1 + it % 5 }
        val ids = LongArray(n) { it.toLong() }
        val objective = FactorizationObjective(x, RankingPairs.of(grades, ids, ids, IntArray(n) { it }), 0.3, 3)
        val theta = DoubleArray(objective.size + 1) { random.nextDouble() * 0.4 - 0.2 }
        val g = DoubleArray(objective.size + 1)
        objective.at(theta, g)
        val h = 1e-3
        for (k in 0 until objective.size) {
            val plus = theta.copyOf().also { it[k] += h }
            val minus = theta.copyOf().also { it[k] -= h }
            val numeric = (objective.at(plus, DoubleArray(g.size)) - objective.at(minus, DoubleArray(g.size))) / (2 * h)
            assertTrue(abs(numeric - g[k]) < 2e-3 * maxOf(1.0, abs(numeric)), "θ[$k]: analytic ${g[k]}, numeric $numeric")
        }
    }

    @Test
    fun `two features that count only together are learnt, which the line cannot`() {
        // The grade is the product of features 0 and 1 alone: each of them by itself tells nothing
        val random = Random(5)
        val n = 240
        val d = 8
        val x = Matrix(n, d)
        val grades = IntArray(n) { i ->
            for (k in 0 until d) x.held[i * d + k] = random.nextFloat() * 2 - 1
            val s = x.held[i * d] * x.held[i * d + 1]
            when {
                s > 0.3f -> 5
                s > 0.1f -> 4
                s > -0.1f -> 3
                s > -0.3f -> 2
                else -> 1
            }
        }
        val ids = LongArray(n) { it.toLong() }
        val task = RankingTask(x, grades, ids, ids)
        val train = IntArray(180) { it }
        val test = (180 until n).toList()
        val machine = FactorizationScorer().fit(task, train, 1.0).scores(x)
        val line = PairwiseLine.fit(task, train).scores(x)
        val machineOrder = concordance(machine, grades, test)
        val lineOrder = concordance(line, grades, test)
        assertTrue(machineOrder > 0.75, "the machine orders $machineOrder of the held-out pairs")
        assertTrue(lineOrder < 0.65, "the line orders $lineOrder")
    }

    @Test
    fun `a machine kept is the machine`() {
        val random = Random(9)
        val n = 30
        val d = 6
        val x = Matrix(n, d)
        for (k in 0 until n * d) x.held[k] = random.nextFloat()
        val grades = IntArray(n) { 1 + it % 5 }
        val ids = LongArray(n) { it.toLong() }
        val model = FactorizationScorer().fit(RankingTask(x, grades, ids, ids), IntArray(n) { it }, 0.3)
        assertEquals(model.scores(x).toList(), FactorizationScorer().unpack(model.pack()).scores(x).toList())
    }

    /** The share of the pairs of [rows] of different grades that [scores] put in their grades' order. */
    private fun concordance(scores: FloatArray, grades: IntArray, rows: List<Int>): Double {
        var right = 0
        var all = 0
        for (a in rows) for (b in rows) {
            if (grades[a] <= grades[b]) continue
            all++
            if (scores[a] > scores[b]) right++
        }
        return right.toDouble() / all
    }

    /** The line alone: the machine with no vectors. */
    private object PairwiseLine {
        fun fit(task: RankingTask, rows: IntArray): FittedScorer = FactorizationScorer.fit(task.x, RankingPairs.of(task, rows), 1.0, rank = 0)
    }
}
