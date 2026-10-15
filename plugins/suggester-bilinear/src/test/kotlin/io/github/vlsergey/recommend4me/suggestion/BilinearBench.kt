package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test

/**
 * The fitting of a real task kept by the application ([SuggestionTaskFile]), measured: BENCH_TASK
 * names the file. Not a test of anything — a measure, run by hand: the fitting from scratch, then
 * the refit after one answer more, gone on from it, as the application makes it.
 */
class BilinearBench {
    @Test
    fun `a kept task fitted, and fitted again after one answer more`() {
        val file = System.getenv("BENCH_TASK")
        assumeTrue(file != null, "BENCH_TASK names a kept task")
        val task = SuggestionTaskFile.read(Path.of(file!!))
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        println("BENCH ${task.itemCount} items × ${task.valueCount} values, ${problem.active.sumOf { it.size.toLong() }} pairs that weigh anything, ${problem.hintPairs} pairs of names in ${problem.hintGroups.size} groups")
        // On the card when there is one, as the application fits
        val onnx = Onnx(defaultModelsDir(), gpu = true)
        var started = System.nanoTime()
        val card = StepOnCard.open(onnx, problem, BilinearSuggester.RANKS.first())
        println("BENCH the task on the card: ${card != null}, in ${(System.nanoTime() - started) / 1_000_000} ms")
        val suggester = BilinearSuggester(onnx)
        // One loss and gradient, and one fold's fitting from scratch, on the processor and on the card
        val where = listOf("processor" to Bilinear(problem, 16), "card" to Bilinear(problem, 16, card))
        for ((where, model) in where.filter { (w, _) -> System.getenv("BENCH_ON") == null || w == System.getenv("BENCH_ON") }) {
            val theta = model.start(1)
            val grad = FloatArray(model.size)
            val trains: (Int) -> Boolean = { problem.foldOf[it] != 0 }
            repeat(3) { model.lossAndGradient(theta, grad, 1e-4f, trains) }
            var t = System.nanoTime()
            repeat(10) { model.lossAndGradient(theta, grad, 1e-4f, trains) }
            val one = (System.nanoTime() - t) / 10 / 1_000_000
            var calls = 0
            t = System.nanoTime()
            Lbfgs.minimize(theta, Bilinear.MAX_STEPS) { x, g -> calls++; model.lossAndGradient(x, g, 1e-4f, trains) }
            println("BENCH on the $where: one loss $one ms; a fold from scratch $calls losses in ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        card?.close()
        // One step of two penalties' five folds each in one product, as a refit takes them
        val alone = Bilinear(problem, 16)
        val count = 2 * BilinearSuggester.FOLDS
        val ensemble = Ensemble(problem, 16, FloatArray(count) { if (it < BilinearSuggester.FOLDS) 1e-6f else 1e-7f }, IntArray(count) { it % BilinearSuggester.FOLDS })
        val thetas = List(count) { alone.start(it + 1L) }
        repeat(2) { ensemble.lossesAndGradients(thetas) }
        val t = System.nanoTime()
        repeat(5) { ensemble.lossesAndGradients(thetas) }
        println("BENCH ten models in one product: one step of them all ${(System.nanoTime() - t) / 5 / 1_000_000} ms")
        if (System.getenv("BENCH_WHOLE") == null) return
        started = System.nanoTime()
        val scratch = suggester.fit(task)!!
        println("BENCH from scratch: ${(System.nanoTime() - started) / 1_000_000} ms")
        // One answer more: a value the user gives a work of a universe that it had not
        val item = task.assigned.indices.first { task.assigned[it].isNotEmpty() && task.allowed[it] != null }
        val value = task.allowed[item]!!.first { it !in task.assigned[item] }
        val assigned = task.assigned.toMutableList().also { it[item] = it[item] + value }
        val changed = SuggestionTask(
            task.itemIds, task.inputs, task.values, task.valueKeys, assigned, task.confirmed, task.rejected, task.context, task.contextKeys,
            task.mentions, task.allowed, task.groupings, task.lackWeight, task.examples, task.hints, task.hintTexts, task.drafts, task.draftWeight,
        )
        started = System.nanoTime()
        suggester.fit(changed, suggester.unpack(scratch.pack(), changed))
        println("BENCH after one answer more, from the last state: ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
