package io.github.vlsergey.recommend4me.suggestion

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
        println("BENCH ${task.itemCount} items × ${task.valueCount} values, ${problem.active.sumOf { it.size.toLong() }} pairs that weigh anything, ${problem.hintPairs} pairs of names")
        val suggester = BilinearSuggester()
        var started = System.nanoTime()
        val scratch = suggester.fit(task)!!
        println("BENCH from scratch: ${(System.nanoTime() - started) / 1_000_000} ms")
        // One answer more: a value the user gives a work of a universe that it had not
        val item = task.assigned.indices.first { task.assigned[it].isNotEmpty() && task.allowed[it] != null }
        val value = task.allowed[item]!!.first { it !in task.assigned[item] }
        val assigned = task.assigned.toMutableList().also { it[item] = it[item] + value }
        val changed = SuggestionTask(
            task.itemIds, task.inputs, task.values, task.valueKeys, assigned, task.confirmed, task.rejected, task.context, task.contextKeys,
            task.mentions, task.allowed, task.groupings, task.lackWeight, task.examples, task.hints,
        )
        started = System.nanoTime()
        suggester.fit(changed, suggester.unpack(scratch.pack(), changed))
        println("BENCH after one answer more, from the last state: ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
