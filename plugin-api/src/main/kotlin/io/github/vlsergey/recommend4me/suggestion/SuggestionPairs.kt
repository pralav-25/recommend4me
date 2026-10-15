package io.github.vlsergey.recommend4me.suggestion

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * THE PAIRS EVERY SUGGESTER IS JUDGED BY, ALIKE — so that the held-out losses of two methods
 * compare on the page of the models. Of every item, the values whose pair with it weighs anything,
 * in ascending order, with the weight and the label of each: when the item has any value, every
 * value it may have ([SuggestionTask.allowed]) weighs [SuggestionTask.lackWeight] as one it lacks;
 * one the user rejected weighs 1, labelled 0; one it has weighs 1, labelled 1 — a draft the user has
 * not confirmed [SuggestionTask.draftWeight]. The items are dealt into [FOLDS] folds by their ids,
 * the same way every time: a work one method holds out, every other holds out too.
 */
class SuggestionPairs(task: SuggestionTask) {
    val n = task.itemCount
    val m = task.valueCount

    /** Of every item, its pairs' values, ascending; their weights and labels. */
    val active: Array<IntArray> = Array(n) { IntArray(0) }
    val weight: Array<FloatArray> = Array(n) { FloatArray(0) }
    val label: Array<FloatArray> = Array(n) { FloatArray(0) }

    /** The fold of every item. */
    val foldOf = IntArray(n) { foldOf(task.itemIds[it]) }

    init {
        // A row of every pair's weight and label, an item at a time: of the millions only those that weigh anything are kept
        val row = FloatArray(m)
        val labels = FloatArray(m)
        for (i in 0 until n) {
            row.fill(0f)
            labels.fill(0f)
            val has = task.assigned[i]
            if (has.isNotEmpty()) {
                val lack = task.lackWeight.toFloat()
                val allowed = task.allowed[i]
                if (allowed == null) row.fill(lack) else allowed.forEach { if (it in 0 until m) row[it] = lack }
            }
            task.rejected[i].forEach { row[it] = 1f }
            has.forEach { row[it] = 1f; labels[it] = 1f }
            val drafted = task.drafts[i]
            if (drafted.isNotEmpty()) {
                val confirmed = task.confirmed[i].toHashSet()
                val w = task.draftWeight.toFloat()
                drafted.forEach { if (labels[it] > 0f && it !in confirmed) row[it] = w }
            }
            val at = (0 until m).filter { row[it] != 0f }.toIntArray()
            active[i] = at
            weight[i] = FloatArray(at.size) { row[at[it]] }
            label[i] = FloatArray(at.size) { labels[at[it]] }
        }
    }

    /**
     * The held-out loss of a method: of every fold, the chances [chances] gives the items of the fold
     * by what it learnt of the others — of each item its chance of every value of [active] — their
     * pairs' logistic losses weighed and summed.
     */
    fun heldOut(chances: (fold: Int, items: IntArray) -> List<FloatArray>): HeldLoss {
        val held = HeldLoss()
        for (fold in 0 until FOLDS) {
            val items = (0 until n).filter { foldOf[it] == fold && active[it].isNotEmpty() }.toIntArray()
            if (items.isEmpty()) continue
            val chancesOf = chances(fold, items)
            items.forEachIndexed { k, i ->
                val c = chancesOf[k]
                active[i].forEachIndexed { j, v -> held.add(weight[i][j].toDouble(), logLoss(c[v].toDouble(), label[i][j])) }
            }
        }
        return held
    }

    companion object {
        /** How many folds the items are dealt into. */
        const val FOLDS = 5

        /** The fold of an item by its id: the same every time, whatever else the task holds. */
        fun foldOf(id: String, folds: Int = FOLDS): Int = (id.hashCode() and Int.MAX_VALUE) % folds

        /** A chance kept off 0 and 1: a certainty proved wrong would weigh without bound. */
        private const val EDGE = 1e-6

        /** The logistic loss of a chance [p] of a pair labelled [y]. */
        fun logLoss(p: Double, y: Float): Double {
            val q = p.coerceIn(EDGE, 1 - EDGE)
            return -(y * ln(q) + (1 - y) * ln(1 - q))
        }
    }
}

/** The weighted held-out losses of pairs, summed: Σw, Σw·l, Σw·l², Σw² — the mean and its standard error. */
class HeldLoss {
    private var weight = 0.0
    private var loss = 0.0
    private var squares = 0.0
    private var weights = 0.0

    val mean: Double get() = if (weight > 0) loss / weight else Double.MAX_VALUE

    fun add(w: Double, l: Double) {
        weight += w
        loss += w * l
        squares += w * l * l
        weights += w * w
    }

    /** The standard error of [mean]: the weighted spread over the effective number of pairs. */
    val error: Double get() {
        if (weight <= 0 || weights <= 0) return Double.MAX_VALUE
        val variance = (squares / weight - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance / (weight * weight / weights))
    }
}

/**
 * The one-standard-error rule (Breiman et al.): of the settings that hold out no worse than the best
 * by more than its standard error, the one [simpler] puts first — the data cannot tell them apart,
 * and the simpler guesses less where an item is unlike those it learnt from.
 */
fun <T> oneStandardError(tried: List<T>, held: (T) -> HeldLoss, simpler: Comparator<T>): T {
    val least = tried.minBy { held(it).mean }
    val bound = held(least).mean + held(least).error
    return tried.filter { held(it).mean <= bound }.sortedWith(simpler).first()
}
