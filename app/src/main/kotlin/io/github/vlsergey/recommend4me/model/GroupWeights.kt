package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.scorer.Scorer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Columns of the model's input weighed as one: a large part of it (a text, the pictures, a facet of many values), or every small part together. */
class ColumnGroup(val part: String, val columns: IntArray)

/** The weights of the groups chosen, and the quality they were chosen by. */
class TunedWeights(val weights: DoubleArray, val metrics: RankingMetrics?)

/**
 * THE WEIGHT OF EVERY PART OF THE INPUT, CHOSEN BY CROSS-VALIDATION. The input is scaled a
 * coordinate at a time ([FeatureLayout]): a text of a thousand coordinates weighs as a thousand
 * one-hot features, and the one penalty of the scorer cannot hold one part back more than another.
 * A group's columns multiplied by its weight are the same as a penalty of their own: the weight 0
 * leaves the group out, 2 lets it speak louder.
 *
 * The weights are chosen a group at a time over [GRID] — every group, in turns, until a turn changes
 * nothing ([PASSES] at most) — each weighing measured by the cross-validation the scorer's parameter
 * is chosen by, the parameter chosen again with it. Of the weights no worse than the best by its
 * standard error, the one nearest to 1: a group is weighed otherwise only when the grades say so.
 */
object GroupWeights {
    /** The weights a group is tried with. */
    val GRID = doubleArrayOf(0.0, 0.25, 0.5, 1.0, 2.0)

    /** How many turns over the groups at most. */
    const val PASSES = 2

    /** A part of this many columns or more is a group of its own; the smaller ones are one group together. */
    const val OWN_GROUP = 100

    /** The group of every small part. */
    const val REST = "rest"

    /** The groups of the [columns] of every part: the large ones by themselves, the rest together. */
    fun groups(columns: Map<String, List<Int>>): List<ColumnGroup> {
        val (large, small) = columns.entries.partition { it.value.size >= OWN_GROUP }
        val rest = small.flatMap { it.value }
        return large.map { ColumnGroup(it.key, it.value.toIntArray()) } +
            listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { ColumnGroup(REST, it.toIntArray()) })
    }

    /** [m] with every group's columns multiplied by its weight; [m] itself when every weight is 1. */
    fun scaled(m: Matrix, groups: List<ColumnGroup>, weights: DoubleArray): Matrix {
        if (weights.all { it == 1.0 }) return m
        val out = Matrix(m.rows, m.cols)
        out.held.copy(m.held, 0, 0, m.rows * m.cols)
        groups.forEachIndexed { g, group ->
            val w = weights[g].toFloat()
            if (w == 1f) return@forEachIndexed
            for (r in 0 until m.rows) {
                val at = out.row(r)
                group.columns.forEach { out.held[at + it] = out.held[at + it] * w }
            }
        }
        return out
    }

    /** The quality of [weights]: the scorer's parameter chosen by the cross-validation of the task weighed so. */
    fun measure(task: RankingTask, xFor: ((Set<Long>) -> Matrix)?, scorer: Scorer, groups: List<ColumnGroup>, weights: DoubleArray): RankingCandidate? {
        val weighed = RankingTask(scaled(task.x, groups, weights), task.grades, task.works, task.items)
        val weighedFor = xFor?.let { f -> { underTest: Set<Long> -> scaled(f(underTest), groups, weights) } }
        return CrossValidation.choose(CrossValidation.measureAll(weighed, scorer, weighedFor))
    }

    /** The weights of the [groups] chosen on [task] (see the class), from every weight 1. */
    fun tune(task: RankingTask, xFor: ((Set<Long>) -> Matrix)?, scorer: Scorer, groups: List<ColumnGroup>): TunedWeights {
        val weights = DoubleArray(groups.size) { 1.0 }
        var current = measure(task, xFor, scorer, groups, weights)?.metrics ?: return TunedWeights(weights, null)
        repeat(PASSES) {
            var changed = false
            groups.indices.forEach { g ->
                val tried = GRID.associateWith { w ->
                    if (w == weights[g]) current
                    else measure(task, xFor, scorer, groups, weights.copyOf().also { it[g] = w })?.metrics
                }.filterValues { it != null }.mapValues { it.value!! }
                val best = tried.values.maxOf { it.concordance }
                val limit = best - (tried.values.maxBy { it.concordance }.concordanceSe)
                // Of those within the error of the best, the nearest to 1 — the weight of no choice
                val chosen = tried.filterValues { it.concordance >= limit }.keys.minBy { distanceFromOne(it) }
                if (chosen != weights[g]) {
                    weights[g] = chosen
                    current = tried.getValue(chosen)
                    changed = true
                }
            }
            if (!changed) return TunedWeights(weights, current)
        }
        return TunedWeights(weights, current)
    }

    /** How far a weight is from 1, on the scale of ratios; 0 the farthest of all. */
    private fun distanceFromOne(w: Double): Double = if (w <= 0.0) Double.MAX_VALUE else abs(ln(w))

    /** One fold of the nested cross-validation: the quality on its works of every weight 1 and of the weights chosen without them. */
    class NestedFold(val baseline: Double, val weighed: Double, val weights: DoubleArray)

    /**
     * THE GAIN OF THE WEIGHTS, MEASURED HONESTLY: every fold of the works is left out in turn; on
     * the rest the weights are chosen ([tune]) and the parameter with them, a model is trained, and
     * the fold's works are ranked — the works the choice never saw. Every weight 1 is measured the
     * same way, fold by fold: the difference is paired. [base] gives the rows with the works under
     * test left out of what depends on them ([Prepared.xFor] of the model; the task's own rows when
     * nothing does).
     */
    fun nested(task: RankingTask, base: ((Set<Long>) -> Matrix)?, scorer: Scorer, groups: List<ColumnGroup>): List<NestedFold> {
        val grades = task.grades
        val works = task.works
        val folds = CrossValidation.folds(grades, works)
        val k = (folds.maxOrNull() ?: 0) + 1
        if (k < 2) return emptyList()
        val ungraded = task.ungraded.toList()
        return (0 until k).map { fold ->
            val inner = grades.indices.filter { folds[it] != fold }
            val test = grades.indices.filter { folds[it] == fold }.toIntArray()
            val outerWorks = test.map { works[it] }.toSet()
            val rowsAll = base?.invoke(outerWorks) ?: task.x
            // The rows the choice sees: the graded ones of the other folds, and every row without a grade
            val kept = (inner + ungraded).toIntArray()
            val innerTask = RankingTask(rowsAll.gather(kept), IntArray(inner.size) { grades[inner[it]] }, LongArray(kept.size) { works[kept[it]] }, LongArray(kept.size) { task.items[kept[it]] })
            val innerFor = base?.let { f -> { underTest: Set<Long> -> f(outerWorks + underTest).gather(kept) } }
            fun ranked(weights: DoubleArray): Double {
                val chosen = measure(innerTask, innerFor, scorer, groups, weights)?.parameter ?: scorer.candidates.first()
                val weighed = RankingTask(scaled(innerTask.x, groups, weights), innerTask.grades, innerTask.works, innerTask.items)
                val fitted = scorer.fit(weighed, IntArray(inner.size) { it }, chosen)
                val scores = fitted.scores(scaled(rowsAll, groups, weights)).map { it.toDouble() }.toDoubleArray()
                return CrossValidation.concordance(grades, works, test, scores) ?: 0.5
            }
            val baseline = ranked(DoubleArray(groups.size) { 1.0 })
            val tuned = tune(innerTask, innerFor, scorer, groups)
            NestedFold(baseline, ranked(tuned.weights), tuned.weights)
        }
    }

    /** The mean of [values] and its standard error. */
    fun meanAndError(values: List<Double>): Pair<Double, Double> {
        val mean = values.average()
        val se = if (values.size > 1) sqrt(values.sumOf { (it - mean).pow(2) } / (values.size - 1) / values.size) else 0.0
        return mean to se
    }
}
