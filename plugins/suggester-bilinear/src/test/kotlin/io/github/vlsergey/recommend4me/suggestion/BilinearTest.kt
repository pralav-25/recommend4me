package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
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
            hints = List(6) { k -> NameHint(FloatArray(d) { random.nextGaussian().toFloat() }, k % m, (0 until m).filter { it != k % m }.toIntArray()) },
        )
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
