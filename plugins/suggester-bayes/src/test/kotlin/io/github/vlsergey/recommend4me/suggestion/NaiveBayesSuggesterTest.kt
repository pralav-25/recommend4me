package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NaiveBayesSuggesterTest {

    /** Of 60 works, those of the fandom "a" have the value 0, those of "b" the value 1 — the fandom tells it alone. */
    private fun task(): SuggestionTask {
        val n = 60
        return SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = emptyList(),
            values = Matrix(2, 4),
            valueKeys = listOf("v0", "v1"),
            assigned = List(n) { intArrayOf(it % 2) },
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { intArrayOf(it % 2) },
            contextKeys = listOf("fandom\u0001a", "fandom\u0001b"),
        )
    }

    @Test
    fun `the values of the other facets tell a work's value, judged on works held out`() {
        val task = task()
        val fitted = assertNotNull(NaiveBayesSuggester().fit(task, null))
        val report = assertNotNull(fitted.report)
        assertTrue(report.tried.size == 9 && report.fromScratch)
        // Held out, it is far better than a coin: the loss of a chance of one half is ln 2
        assertTrue(report.loss < 0.3, "loss ${report.loss}")
        // Scored by every other work's counts, its own taken out
        val chances = fitted.on(task).of(intArrayOf(0, 1))
        assertTrue(chances[0][0] > 0.8f && chances[0][1] < 0.2f, chances[0].toList().toString())
        assertTrue(chances[1][1] > 0.8f && chances[1][0] < 0.2f, chances[1].toList().toString())
        // Packed: the settings alone; the counts are the task's
        val unpacked = assertNotNull(NaiveBayesSuggester().unpack(fitted.pack(), task))
        assertTrue(unpacked.on(task).of(intArrayOf(0))[0][0] > 0.8f)
    }
}
