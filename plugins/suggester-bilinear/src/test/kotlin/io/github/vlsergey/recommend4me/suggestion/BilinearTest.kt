package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

class BilinearTest {

    /** A small task with every kind of input: two text blocks, a context, examples, mentions, names of the values as hints. */
    private fun task(): SuggestionTask {
        val random = Random(5)
        val n = 12
        val d = 6
        val m = 4
        fun block(name: String, every: Int) = Matrix(n, d).let { b ->
            val present = BooleanArray(n) { it % every != 0 }
            for (i in 0 until n) if (present[i]) b.held.put(FloatArray(d) { random.nextGaussian().toFloat() }, 0, b.row(i), d)
            InputBlock(name, b, present)
        }
        val names = Matrix(m, d).also { v -> for (j in 0 until m) v.held.put(FloatArray(d) { random.nextGaussian().toFloat() }, 0, v.row(j), d) }
        return SuggestionTask(
            itemIds = List(n) { "i$it" },
            inputs = listOf(block("text:title", 5), block("text:annotation", 3)),
            values = names,
            valueKeys = List(m) { "v$it" },
            assigned = List(n) { i -> if (i % 4 == 0) IntArray(0) else intArrayOf(i % m) },
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { i -> if (i == 3) intArrayOf(1) else IntArray(0) },
            context = List(n) { i -> intArrayOf(i % 2) },
            contextKeys = listOf("f\u0001a", "f\u0001b"),
            mentions = List(n) { i -> if (i % 3 == 0) mapOf(i % m to 2) else null },
            lackWeight = 0.5,
            examples = List(n) { i -> if (i % 2 == 0) intArrayOf(i % m) else IntArray(0) },
            hints = IntArray(m) { it }.let { all -> List(6) { k -> NameHint(k % m, all) } },
            hintTexts = Matrix(6, d).also { t -> for (k in 0 until 6) t.held.put(FloatArray(d) { random.nextGaussian().toFloat() }, 0, t.row(k), d) },
        )
    }

    @Test
    fun `a step on the card is the processor's`() {
        val task = task()
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        val card = StepOnCard.open(Onnx(defaultModelsDir(), gpu = true), problem, 3)
        assumeTrue(card != null, "no card to run on")
        card.use {
            val onProcessor = Bilinear(problem, 3)
            val onCard = Bilinear(problem, 3, card)
            val random = Random(11)
            val theta = FloatArray(onProcessor.size) { (random.nextGaussian() * 0.3).toFloat() }
            val trains: (Int) -> Boolean = { problem.foldOf[it] != 0 }
            val g1 = FloatArray(onProcessor.size)
            val g2 = FloatArray(onProcessor.size)
            val l1 = onProcessor.lossAndGradient(theta, g1, 0.01f, trains)
            val l2 = onCard.lossAndGradient(theta, g2, 0.01f, trains)
            assertTrue(abs(l1 - l2) < 1e-4 * abs(l1), "loss $l1 on the processor, $l2 on the card")
            // Floats added in another order on the card: the last digits differ, where a wrong graph
            // would be wrong by the whole
            val worst = g1.indices.maxOf { abs(g1[it] - g2[it]) / max(1e-4f, abs(g1[it])) }
            assertTrue(worst < 1e-3, "the worst relative difference of the gradient $worst")
        }
    }

    @Test
    fun `the gradient is the loss's own, the names' part with it`() {
        val task = task()
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        val model = Bilinear(problem, 3)
        val random = Random(9)
        val theta = FloatArray(model.size) { (random.nextGaussian() * 0.3).toFloat() }
        val grad = FloatArray(model.size)
        val trains: (Int) -> Boolean = { problem.foldOf[it] != 0 }
        model.lossAndGradient(theta, grad, 0.01f, trains)
        val scratch = FloatArray(model.size)
        var worst = 0.0
        for (k in theta.indices step max(1, theta.size / 200)) {
            // In single precision a central difference needs a step far above the float's resolution of the loss
            val eps = 1e-2f
            val plus = theta.copyOf().also { it[k] += eps }
            val minus = theta.copyOf().also { it[k] -= eps }
            val numeric = (model.lossAndGradient(plus, scratch, 0.01f, trains) - model.lossAndGradient(minus, scratch, 0.01f, trains)).toDouble() / (2 * eps)
            worst = max(worst, abs(numeric - grad[k]) / max(1e-3, abs(numeric) + abs(grad[k])))
        }
        assertTrue(worst < 0.02, "the worst relative error $worst")
    }
}
