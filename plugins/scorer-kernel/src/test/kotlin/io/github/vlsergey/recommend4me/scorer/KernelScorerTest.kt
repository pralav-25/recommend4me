package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KernelScorerTest {

    @Test
    fun `the gradient is the loss's`() {
        val random = Random(3)
        val n = 10
        val d = 4
        val x = Matrix(n, d)
        for (k in 0 until n * d) x.held[k] = random.nextFloat() * 2 - 1
        val grades = IntArray(n) { 1 + it % 5 }
        val ids = LongArray(n) { it.toLong() }
        val objective = KernelObjective(Kernel.of(x, x), n, RankingPairs.of(grades, ids, ids, IntArray(n) { it }), 0.3)
        val theta = DoubleArray(n + 1) { random.nextDouble() - 0.5 }
        val g = DoubleArray(n + 1)
        objective.at(theta, g)
        val h = 1e-3
        for (k in 0 until n) {
            val plus = theta.copyOf().also { it[k] += h }
            val minus = theta.copyOf().also { it[k] -= h }
            val numeric = (objective.at(plus, DoubleArray(g.size)) - objective.at(minus, DoubleArray(g.size))) / (2 * h)
            assertTrue(abs(numeric - g[k]) < 2e-3 * maxOf(1.0, abs(numeric)), "α[$k]: analytic ${g[k]}, numeric $numeric")
        }
    }

    @Test
    fun `a taste of two kinds is learnt, which the line cannot`() {
        // Liked far out either way along x0 + x1, not liked in between: two kinds of liked works
        val random = Random(5)
        val n = 240
        val d = 6
        val x = Matrix(n, d)
        val grades = IntArray(n) { i ->
            for (k in 0 until d) x.held[i * d + k] = random.nextFloat() * 2 - 1
            val s = abs(x.held[i * d] + x.held[i * d + 1])
            when {
                s > 1.2f -> 5
                s > 0.8f -> 4
                s > 0.5f -> 3
                s > 0.25f -> 2
                else -> 1
            }
        }
        val ids = LongArray(n) { it.toLong() }
        val task = RankingTask(x, grades, ids, ids)
        val train = IntArray(180) { it }
        val test = (180 until n).toList()
        val kernel = KernelScorer().fit(task, train, 1.0).scores(x)
        val line = lineScores(task, train)
        val kernelOrder = concordance(kernel, grades, test)
        val lineOrder = concordance(line, grades, test)
        assertTrue(kernelOrder > 0.75, "the kernel orders $kernelOrder of the held-out pairs")
        assertTrue(lineOrder < 0.65, "the line orders $lineOrder")
    }

    @Test
    fun `a model kept is the model`() {
        val random = Random(9)
        val n = 25
        val d = 5
        val x = Matrix(n, d)
        for (k in 0 until n * d) x.held[k] = random.nextFloat()
        val grades = IntArray(n) { 1 + it % 5 }
        val ids = LongArray(n) { it.toLong() }
        val model = KernelScorer().fit(RankingTask(x, grades, ids, ids), IntArray(n) { it }, 0.3)
        assertEquals(model.scores(x).toList(), KernelScorer().unpack(model.pack()).scores(x).toList())
    }

    /** The line, fitted the same way: the gradient of the pairs' loss by w is Xᵀq. */
    private fun lineScores(task: RankingTask, rows: IntArray): FloatArray {
        val pairs = RankingPairs.of(task, rows)
        val x = task.x
        val d = x.cols
        val w = DoubleArray(d)
        val z = io.github.vlsergey.recommend4me.matrix.Floats(x.rows)
        val q = io.github.vlsergey.recommend4me.matrix.Floats(x.rows)
        repeat(2000) {
            for (i in 0 until x.rows) z[i] = (0 until d).sumOf { k -> w[k] * x.held[i * d + k] }.toFloat()
            q.clear()
            pairs.loss(z, q)
            for (k in 0 until d) w[k] -= 0.5 * ((0 until x.rows).sumOf { i -> q[i] * x.held[i * d + k].toDouble() } / pairs.weight + w[k])
        }
        return FloatArray(x.rows) { i -> (0 until d).sumOf { k -> w[k] * x.held[i * d + k] }.toFloat() }
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
}
