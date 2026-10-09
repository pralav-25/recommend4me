package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Floats
import kotlin.math.exp
import kotlin.math.ln

/**
 * WHAT A RANKING IS LEARNT FROM: the pairs of rows of a [RankingTask] whose order is known.
 *
 * Rated rows [better] and [worse]: different grades, different works; every pair of works weighs
 * the same. Not every pair of grades: the works graded "do not like it" are most of the rated, and
 * telling them from the rest is most of what a model is for — weighed as one pair of grades of
 * ten, a pair of grades of four works weighed as much.
 *
 * And the rows of two items of one work, [first] and [second]: the work on two sites, EQUAL —
 * graded or not, their loss is least when their scores are the same. A pair of equals weighs
 * [equalWeight]: as much as all the pairs of grades of an average graded row together, so that
 * the two items of a work are held together as firmly as a graded work is held in its place —
 * whether the work has a grade or not.
 */
class RankingPairs(val better: IntArray, val worse: IntArray, val first: IntArray, val second: IntArray, val equalWeight: Double) {
    /** The weight of all the pairs together. */
    val weight: Double get() = better.size + equalWeight * first.size

    /**
     * The pairwise logistic loss of the scores [z] of the rows — of score(better) − score(worse),
     * of an equal pair the loss of a pair either way round with the chance of a half — summed;
     * its derivative by every row's score added to [dz].
     */
    fun loss(z: Floats, dz: Floats): Double {
        var loss = 0.0
        for (p in better.indices) {
            val s = (z[better[p]] - z[worse[p]]).toDouble()
            loss += softplus(-s)
            val r = sigmoid(s) - 1.0
            dz[better[p]] = dz[better[p]] + r.toFloat()
            dz[worse[p]] = dz[worse[p]] - r.toFloat()
        }
        for (p in first.indices) {
            val s = (z[first[p]] - z[second[p]]).toDouble()
            // −½ log σ(s) − ½ log σ(−s): least at s = 0
            loss += equalWeight * 0.5 * (softplus(-s) + softplus(s))
            val r = equalWeight * (sigmoid(s) - 0.5)
            dz[first[p]] = dz[first[p]] + r.toFloat()
            dz[second[p]] = dz[second[p]] - r.toFloat()
        }
        return loss
    }

    companion object {
        /** The pairs of the graded rows [rows] of [task] and of its rows without a grade, which only have equals. */
        fun of(task: RankingTask, rows: IntArray): RankingPairs = of(task.grades, task.works, task.items, rows, task.ungraded)

        /** The pairs of the graded rows [subset] and of the rows without a grade, [ungraded], which only have equals. */
        fun of(grades: IntArray, works: LongArray, items: LongArray, subset: IntArray, ungraded: IntRange = IntRange.EMPTY): RankingPairs {
            val better = ArrayList<Int>()
            val worse = ArrayList<Int>()
            for (a in subset) for (b in subset) {
                if (works[a] != works[b] && grades[a] > grades[b]) {
                    better += a
                    worse += b
                }
            }
            val first = ArrayList<Int>()
            val second = ArrayList<Int>()
            (subset.asSequence() + ungraded.asSequence()).groupBy { works[it] }.values.forEach { rows ->
                for (a in rows) for (b in rows) {
                    if (items[a] < items[b]) {
                        first += a
                        second += b
                    }
                }
            }
            // Every pair of grades is a pair of two graded rows
            val equalWeight = if (subset.isEmpty()) 0.0 else 2.0 * better.size / subset.size
            return RankingPairs(better.toIntArray(), worse.toIntArray(), first.toIntArray(), second.toIntArray(), equalWeight)
        }

        fun sigmoid(s: Double): Double = 1.0 / (1.0 + exp(-s))

        /** log(1 + exp(s)) without overflow. */
        fun softplus(s: Double): Double = if (s > 0) s + ln(1.0 + exp(-s)) else ln(1.0 + exp(s))
    }
}
