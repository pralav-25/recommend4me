package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairwiseScorerTest {

    @Test
    fun `the line learns the order of grades from pairs alone`() {
        val random = Random(7)
        val n = 120
        val d = 30
        val x = Matrix(n, d)
        val grades = IntArray(n) { i ->
            for (k in 0 until d) x.held[i * d + k] = random.nextFloat() * 2 - 1
            val s = x.held[i * d] + 0.5f * x.held[i * d + 1]
            when {
                s > 0.6f -> 5
                s > 0.2f -> 4
                s > -0.2f -> 3
                s > -0.6f -> 2
                else -> 1
            }
        }
        val task = RankingTask(x, grades, LongArray(n) { it.toLong() }, LongArray(n) { it.toLong() })
        val model = PairwiseScorer().fit(task, IntArray(n) { it }, 1.0) as LinearModel
        assertTrue(model.w[0] > 0 && model.w[1] > 0 && model.w[0] > model.w[1])

        val again = PairwiseScorer().unpack(model.pack())
        assertEquals(model.scores(x).toList(), again.scores(x).toList())
    }

    @Test
    fun `pairs are of different grades and different works`() {
        val grades = intArrayOf(1, 1, 1, 1, 2, 3)
        val works = longArrayOf(1, 2, 3, 4, 5, 5)
        // The last two are two versions of one item
        val items = longArrayOf(1, 2, 3, 4, 5, 5)
        val pairs = RankingPairs.of(grades, works, items, IntArray(grades.size) { it })
        // 3 over 1 (four), 2 over 1 (four); 3 and 2 are one work
        assertEquals(8, pairs.better.size)
        assertEquals(0, pairs.first.size)
        // Six graded rows in eight pairs: a pair of equals would weigh 2 · 8 / 6
        assertEquals(16.0 / 6, pairs.equalWeight, 1e-9)
        assertTrue(pairs.better.indices.all { grades[pairs.better[it]] > grades[pairs.worse[it]] })
    }

    @Test
    fun `two items of one work are a pair of equals, whatever their grades`() {
        val grades = intArrayOf(1, 4, 3)
        val works = longArrayOf(1, 2, 2)
        val items = longArrayOf(1, 2, 3)
        val pairs = RankingPairs.of(grades, works, items, IntArray(grades.size) { it })
        // 4 over 1, 3 over 1; 4 and 3 are one book on two sites: equal, once
        assertEquals(2, pairs.better.size)
        assertEquals(listOf(1 to 2), pairs.first.indices.map { pairs.first[it] to pairs.second[it] })
    }

    @Test
    fun `an ungraded item of a work is an equal of its other items, and of nothing else`() {
        val grades = intArrayOf(1, 4)
        // Rows 2 and 3: ungraded; 2 is one work with the graded row 1, 3 with nothing graded
        val works = longArrayOf(1, 2, 2, 3)
        val items = longArrayOf(1, 2, 3, 4)
        val pairs = RankingPairs.of(grades, works, items, intArrayOf(0, 1), 2 until 4)
        assertEquals(listOf(1 to 0), pairs.better.indices.map { pairs.better[it] to pairs.worse[it] })
        assertEquals(listOf(1 to 2), pairs.first.indices.map { pairs.first[it] to pairs.second[it] })
    }

    @Test
    fun `an ungraded item takes the place of the work it is linked into`() {
        // Feature 0 orders the graded rows; the ungraded item has feature 1 only, which no grade
        // tells anything of — but it is the same work as the best graded one
        val n = 41
        val x = Matrix(n, 2)
        val graded = n - 1
        val grades = IntArray(graded) { i ->
            x.held[i * 2] = i / graded.toFloat()
            1 + i * 5 / graded
        }
        x.held[graded * 2 + 1] = 1f
        val items = LongArray(n) { it.toLong() }
        val apart = PairwiseScorer().fit(RankingTask(x, grades, items, items), IntArray(graded) { it }, 1.0).scores(x)
        val works = LongArray(n) { if (it == graded) (graded - 1).toLong() else it.toLong() }
        val linked = PairwiseScorer().fit(RankingTask(x, grades, works, items), IntArray(graded) { it }, 1.0).scores(x)
        // Alone it scores as no work at all; linked, about as the best, which it is
        assertEquals(0f, apart[graded])
        val spread = linked[graded - 1] - linked[0]
        val gap = kotlin.math.abs(linked[graded] - linked[graded - 1])
        assertTrue(gap < 0.2f * spread,"${linked[graded]} vs the best ${linked[graded - 1]}, the worst ${linked[0]}")
    }

    @Test
    fun `books on both sites bring the sites' scales together`() {
        // Feature 0: how good the book is; feature 1: the site, +1 for one, −1 for the other. The
        // books of the first site happen to be graded higher, so alone the line takes the site for
        // quality; the same books on both sites, graded alike, say the site tells nothing.
        val random = Random(11)
        val single = 60
        val both = 20
        val n = single + 2 * both
        val x = Matrix(n, 2)
        val grades = IntArray(n)
        val works = LongArray(n) { it.toLong() }
        for (i in 0 until single) {
            val site = if (i % 2 == 0) 1f else -1f
            val quality = random.nextFloat() * 2 - 1
            x.held[i * 2] = quality
            x.held[i * 2 + 1] = site
            grades[i] = ((quality + 1) * 1.5f + (if (site > 0) 2f else 0f)).toInt().coerceIn(1, 5)
        }
        for (j in 0 until both) {
            val quality = random.nextFloat() * 2 - 1
            val grade = ((quality + 1) * 2).toInt().coerceIn(1, 5)
            for ((k, site) in listOf(1f, -1f).withIndex()) {
                val row = single + 2 * j + k
                x.held[row * 2] = quality
                x.held[row * 2 + 1] = site
                grades[row] = grade
                works[row] = (single + j).toLong()
            }
        }
        val items = LongArray(n) { it.toLong() }
        val all = IntArray(n) { it }
        val apart = PairwiseScorer().fit(RankingTask(x, grades, items, items), all, 1.0) as LinearModel
        val linked = PairwiseScorer().fit(RankingTask(x, grades, works, items), all, 1.0) as LinearModel
        // The weight of the site, relative to that of quality
        val siteApart = apart.w[1] / apart.w[0]
        val siteLinked = linked.w[1] / linked.w[0]
        assertTrue(siteLinked < siteApart, "$siteApart → $siteLinked")
    }
}
