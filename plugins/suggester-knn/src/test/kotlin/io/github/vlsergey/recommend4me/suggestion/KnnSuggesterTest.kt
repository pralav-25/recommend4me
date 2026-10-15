package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KnnSuggesterTest {

    /** Of 80 works, the texts of those of the value 0 lie near one direction, of the value 1 near another. */
    private fun task(): SuggestionTask {
        val n = 80
        val d = 16
        val random = Random(11)
        val centres = List(2) { FloatArray(d) { random.nextGaussian().toFloat() } }
        val texts = Matrix(n, d)
        repeat(n) { i ->
            val v = FloatArray(d) { centres[i % 2][it] + 0.3f * random.nextGaussian().toFloat() }
            texts.held.put(v, 0, texts.row(i), d)
        }
        return SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = listOf(InputBlock("text:annotation", texts, BooleanArray(n) { true })),
            values = Matrix(2, 4),
            valueKeys = listOf("v0", "v1"),
            assigned = List(n) { intArrayOf(it % 2) },
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { IntArray(0) },
            contextKeys = emptyList(),
        )
    }

    @Test
    fun `a work has the values of the works nearest it by its texts, judged on works held out`() {
        val task = task()
        // No card: the processor finds the nearest
        val fitted = assertNotNull(KnnSuggester().fit(task, null))
        val report = assertNotNull(fitted.report)
        assertTrue(report.tried.size == 4 && report.fromScratch)
        assertTrue(report.loss < 0.3, "loss ${report.loss}")
        val chances = fitted.on(task).of(intArrayOf(0, 1))
        assertTrue(chances[0][0] > 0.8f && chances[0][1] < 0.2f, chances[0].toList().toString())
        assertTrue(chances[1][1] > 0.8f && chances[1][0] < 0.2f, chances[1].toList().toString())
    }
}
