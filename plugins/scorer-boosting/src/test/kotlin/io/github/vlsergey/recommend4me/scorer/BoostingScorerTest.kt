package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoostingScorerTest {

    @Test
    fun `a feature that counts only beside another is learnt`() {
        // Feature 0 counts only where feature 2 is up — a tag that matters only with an annotation
        // of a kind; feature 1 counts a little everywhere
        val random = Random(5)
        val n = 240
        val d = 8
        val x = Matrix(n, d)
        val grades = IntArray(n) { i ->
            for (k in 0 until d) x.held[i * d + k] = random.nextFloat() * 2 - 1
            val s = (if (x.held[i * d + 2] > 0) x.held[i * d] else 0f) + 0.3f * x.held[i * d + 1]
            when {
                s > 0.4f -> 5
                s > 0.1f -> 4
                s > -0.1f -> 3
                s > -0.4f -> 2
                else -> 1
            }
        }
        val ids = LongArray(n) { it.toLong() }
        val task = RankingTask(x, grades, ids, ids)
        val scores = BoostingScorer().fit(task, IntArray(180) { it }, 200.0).scores(x)
        val order = concordance(scores, grades, (180 until n).toList())
        assertTrue(order > 0.75, "the trees order $order of the held-out pairs")
    }

    @Test
    fun `trees kept are the trees`() {
        val random = Random(9)
        val n = 40
        val d = 6
        val x = Matrix(n, d)
        for (k in 0 until n * d) x.held[k] = random.nextFloat()
        val grades = IntArray(n) { 1 + it % 5 }
        val ids = LongArray(n) { it.toLong() }
        val model = BoostingScorer().fit(RankingTask(x, grades, ids, ids), IntArray(n) { it }, 25.0)
        assertEquals(model.scores(x).toList(), BoostingScorer().unpack(model.pack()).scores(x).toList())
    }

    @Test
    fun `the most trees on the size of the grades there are fit in seconds`() {
        val random = Random(1)
        val n = 150
        val d = 3400
        val x = Matrix(n, d)
        for (k in 0 until n * d) x.held[k] = random.nextFloat()
        val grades = IntArray(n) { 1 + random.nextInt(5) }
        val ids = LongArray(n) { it.toLong() }
        val started = System.currentTimeMillis()
        BoostingScorer().fit(RankingTask(x, grades, ids, ids), IntArray(n) { it }, 400.0)
        val took = System.currentTimeMillis() - started
        assertTrue(took < 20_000, "400 trees in $took ms")
        println("400 trees on $n rows × $d features in $took ms")
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
