package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.rating.Grades
import kotlin.math.ln

/**
 * HOW MUCH A WORK'S DESCRIPTION ANSWERS THOSE OF THE WORKS THE USER GRADED, read by a reranker
 * ([TextReranker][io.github.vlsergey.recommend4me.encoder.TextReranker]) with both texts together:
 * the same trope, the same kind of hero, a plot told in other words — what the vectors of two
 * texts, each read alone, miss.
 *
 * THE STRENGTH IS A SURPRISE, as of the likeness to the marks ([MarkLikeness]): a reranker's scores
 * mean nothing but their order, and some descriptions are alike half the catalogue. So a work's
 * score against a graded work is placed among the scores of every work of the catalogue against
 * it (F), and its strength is −ln(1 − F): chance gives about 1; 5 is one work in 150.
 *
 * TWO AXES A GRADE: the strongest and the second strongest likeness to a work of that grade. A
 * work's own work never counts for it — it would tell its grade — nor, in cross-validation, the
 * works under test ([excluded]).
 */
class WorkLikeness private constructor(
    /** The graded items with scores, and the latest grade and the work of each. */
    private val graded: List<ItemKey>,
    private val gradeOf: IntArray,
    private val gradedWork: LongArray,
    /** Every work with scores, and its place in the rows of [strengths]. */
    private val items: Map<ItemKey, Int>,
    private val workOf: (ItemKey) -> Long,
    /** A row a graded item: the strength of every work's likeness to it, NaN for a pair not read. */
    private val strengths: Array<FloatArray>,
) {
    val empty: Boolean get() = items.isEmpty()

    /** The axes of a work, its own work and the items [excluded] not counted; null for a work with no scores. */
    fun vector(item: ItemKey, excluded: Set<ItemKey> = emptySet()): FloatArray? = items[item]?.let { axes(item, it, excluded) }

    /** The axes of every work with scores, its own work not counted. */
    fun forEach(action: (item: ItemKey, vector: FloatArray) -> Unit) = items.forEach { (item, at) -> action(item, axes(item, at, emptySet())) }

    private fun axes(item: ItemKey, at: Int, excluded: Set<ItemKey>): FloatArray {
        val out = FloatArray(DIM)
        val own = workOf(item)
        for (j in graded.indices) {
            val s = strengths[j][at]
            if (s.isNaN() || gradedWork[j] == own || (excluded.isNotEmpty() && graded[j] in excluded)) continue
            val axis = 2 * (gradeOf[j] - Grades.MIN)
            if (s > out[axis]) {
                out[axis + 1] = out[axis]
                out[axis] = s
            } else if (s > out[axis + 1]) out[axis + 1] = s
        }
        return out
    }

    companion object {
        /** The block of the axes among a work's vectors. */
        const val WORKS = "likeness:works"

        /** Two axes for every grade. */
        val DIM = 2 * (Grades.MAX - Grades.MIN + 1)

        fun none(): WorkLikeness = WorkLikeness(emptyList(), IntArray(0), LongArray(0), emptyMap(), { 0L }, emptyArray())

        /**
         * The likeness from every [scores] (graded item, work, score) of a reranker, against the
         * items [grades] holds — the others are not graded any more and are left out.
         */
        fun of(grades: Map<ItemKey, Int>, workOf: (ItemKey) -> Long, scores: ((graded: ItemKey, item: ItemKey, score: Float) -> Unit) -> Unit): WorkLikeness {
            // The scores as they come, a list a graded item: the places of the works and their scores
            val gradedAt = HashMap<ItemKey, Int>()
            val items = HashMap<ItemKey, Int>()
            val places = ArrayList<IntList>()
            val values = ArrayList<FloatList>()
            scores { graded, item, score ->
                if (graded !in grades) return@scores
                val j = gradedAt.getOrPut(graded) { places += IntList(); values += FloatList(); gradedAt.size }
                places[j].add(items.getOrPut(item) { items.size })
                values[j].add(score)
            }
            val graded = ArrayList<ItemKey>(gradedAt.size).apply { repeat(gradedAt.size) { add(ItemKey("", "")) } }
            gradedAt.forEach { (k, j) -> graded[j] = k }
            val strengths = Array(graded.size) { j ->
                val row = FloatArray(items.size) { Float.NaN }
                val sorted = values[j].toArray().also { it.sort() }
                val n = sorted.size
                for (k in 0 until values[j].size) {
                    val score = values[j][k]
                    val below = lowerBound(sorted, score)
                    val equal = upperBound(sorted, score) - below
                    // Half the work's own place below it; never the whole catalogue, a work is not past every one
                    val f = ((below + equal / 2.0) / n).coerceAtMost(1.0 - 0.5 / n)
                    row[places[j][k]] = (-ln(1.0 - f)).toFloat()
                }
                row
            }
            return WorkLikeness(
                graded, IntArray(graded.size) { grades.getValue(graded[it]) }, LongArray(graded.size) { workOf(graded[it]) },
                items, workOf, strengths,
            )
        }

        /** A growing list of ints without boxing. */
        private class IntList {
            private var a = IntArray(256)
            var size = 0
                private set

            fun add(v: Int) {
                if (size == a.size) a = a.copyOf(size * 2)
                a[size++] = v
            }

            operator fun get(i: Int) = a[i]
        }

        /** A growing list of floats without boxing. */
        private class FloatList {
            private var a = FloatArray(256)
            var size = 0
                private set

            fun add(v: Float) {
                if (size == a.size) a = a.copyOf(size * 2)
                a[size++] = v
            }

            operator fun get(i: Int) = a[i]

            fun toArray(): FloatArray = a.copyOf(size)
        }

        private fun lowerBound(a: FloatArray, v: Float): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (a[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private fun upperBound(a: FloatArray, v: Float): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (a[mid] <= v) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
