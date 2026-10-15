package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.rating.Grades
import kotlin.math.sqrt

/**
 * What the model reads about an item: dense vectors by block key (its texts, its pictures, its sets,
 * its likeness to the marks), categorical features — "<facet>:<value>", "prev:<grade>",
 * "source:<id>" — and numbers by name, as the source declared them
 * transformed ([NumberScale][io.github.vlsergey.recommend4me.source.NumberScale]).
 */
class ItemInput(
    val key: ItemKey,
    val vectors: Map<String, FloatArray>,
    val categorical: Set<String>,
    val numeric: Map<String, Float>,
) {
    fun withVectors(vectors: Map<String, FloatArray>) = ItemInput(key, vectors, categorical, numeric)

    companion object {
        val PREVIOUS_GRADES = Grades.RANGE.map { "prev:$it" }
    }
}

/** A part of the input that an explanation can switch off as a whole. */
sealed interface FeatureGroup {
    val feature: String

    data class Vector(val block: String) : FeatureGroup {
        override val feature get() = block
    }

    data class Categorical(override val feature: String) : FeatureGroup
    data class Numeric(override val feature: String) : FeatureGroup
}

/**
 * Where the vectors of one block lie: their mean, and the typical distance from it.
 *
 * VECTORS ARE CENTRED. The vectors of a text encoder share a large common part — any two texts
 * have a cosine of 0.7–0.9 — so an uncentred vector is mostly "this text is present", and the
 * model learnt which texts were present rather than what they said. Centred, a missing text is the
 * AVERAGE text and adds nothing, and a present one adds what is particular about it. The pictures'
 * vectors are treated the same way: a work whose pictures are not analysed yet is average.
 *
 * AND SCALED so a coordinate is about as large as a one-hot feature: the typical centred vector is
 * stretched to a length of √dim.
 */
class VectorSpread(val center: FloatArray, val spread: Float) {
    companion object {
        /** Mean and spread of every block over the vectors of all items, graded or not. */
        fun of(vectors: (action: (key: String, vector: FloatArray) -> Unit) -> Unit): Map<String, VectorSpread> =
            VectorSpreads().also { s -> vectors(s::add) }.spreads()
    }
}

/**
 * The spreads of the blocks ([VectorSpread]) summed in ONE PASS over the vectors, none of them kept:
 * of every block the sum of its vectors and of their squared lengths — the spread is
 * √(E‖v‖² − ‖E v‖²). The vectors of every work are read as they stream from the files, and only
 * those of the works a training reads are kept ([Catalogue]).
 */
class VectorSpreads {
    private val sums = HashMap<String, DoubleArray>()
    private val squares = HashMap<String, Double>()
    private val counts = HashMap<String, Int>()

    fun add(key: String, v: FloatArray) {
        val sum = sums.getOrPut(key) { DoubleArray(v.size) }
        // A block of another length — an encoder changed, the old vectors still kept — counts nothing
        if (sum.size != v.size) return
        var square = 0.0
        for (k in v.indices) {
            val x = v[k].toDouble()
            sum[k] += x
            square += x * x
        }
        squares.merge(key, square, Double::plus)
        counts.merge(key, 1, Int::plus)
    }

    fun spreads(): Map<String, VectorSpread> = sums.mapValues { (key, sum) ->
        val n = counts.getValue(key)
        val center = FloatArray(sum.size) { (sum[it] / n).toFloat() }
        val centerSquare = sum.sumOf { (it / n) * (it / n) }
        VectorSpread(center, sqrt((squares.getValue(key) / n - centerSquare).coerceAtLeast(0.0)).toFloat().coerceAtLeast(1e-6f))
    }
}

/** How a number is standardised: its mean and spread over the catalogue; a missing number is the mean. */
data class NumberSpread(val mean: Float, val sd: Float)

/**
 * Positions of the features in a model's input row:
 *
 *     [ vector blocks, in the order of their keys | categorical one-hots | numbers ]
 */
class FeatureLayout(
    val categorical: List<String>,
    private val spreads: Map<String, VectorSpread>,
    private val numbers: Map<String, NumberSpread>,
) {
    val blocks: List<String> = spreads.keys.sorted()
    private val dims = blocks.map { spreads.getValue(it).center.size }
    private val blockOffsets = dims.runningFold(0) { at, dim -> at + dim }
    private val blockIndex = blocks.withIndex().associate { (i, b) -> b to i }
    private val categoricalIndex = categorical.withIndex().associate { (i, n) -> n to i }
    val numeric: List<String> = numbers.keys.sorted()
    private val numericIndex = numeric.withIndex().associate { (i, n) -> n to i }

    val categoricalOffset = blockOffsets.last()
    val numericOffset = categoricalOffset + categorical.size
    val width = numericOffset + numeric.size

    fun dimOf(block: String): Int = blockIndex[block]?.let { dims[it] } ?: 0

    /** Where the block starts in a row; null when the layout has no such block. */
    fun offsetOf(block: String): Int? = blockIndex[block]?.let { blockOffsets[it] }

    /** Writes the input into row [row] of [into]; the group [without] is left out (the average). */
    fun write(input: ItemInput, into: Matrix, row: Int, without: FeatureGroup? = null) = write(input, into, row, listOfNotNull(without))

    /** Writes the input into row [row] of [into]; the groups [without] are left out (the average). */
    fun write(input: ItemInput, into: Matrix, row: Int, without: Collection<FeatureGroup>) {
        val base = into.row(row)
        into.held.clear(base, width)
        val vectorsOut = without.filterIsInstance<FeatureGroup.Vector>().map { it.block }.toSet()
        val categoricalOut = without.filterIsInstance<FeatureGroup.Categorical>().map { it.feature }.toSet()
        val numericOut = without.filterIsInstance<FeatureGroup.Numeric>().map { it.feature }.toSet()
        blocks.forEachIndexed { b, block ->
            if (block in vectorsOut) return@forEachIndexed
            val v = input.vectors[block] ?: return@forEachIndexed
            val spread = spreads.getValue(block)
            if (v.size != spread.center.size) return@forEachIndexed
            val at = base + blockOffsets[b]
            val scale = sqrt(dims[b].toFloat()) / spread.spread
            for (k in 0 until dims[b]) into.held[at + k] = (v[k] - spread.center[k]) * scale
        }
        input.categorical.forEach { name ->
            if (name in categoricalOut) return@forEach
            categoricalIndex[name]?.let { into.held[base + categoricalOffset + it] = 1f }
        }
        input.numeric.forEach { (name, v) ->
            if (name in numericOut) return@forEach
            val i = numericIndex[name] ?: return@forEach
            val s = numbers.getValue(name)
            into.held[base + numericOffset + i] = (v - s.mean) / s.sd
        }
    }

    fun matrix(inputs: List<ItemInput>): Matrix {
        val m = Matrix(inputs.size, width)
        inputs.forEachIndexed { i, input -> write(input, m, i) }
        return m
    }

    /** The groups present in the input that the model knows about. */
    fun groups(input: ItemInput): List<FeatureGroup> =
        blocks.filter { input.vectors.containsKey(it) }.map { FeatureGroup.Vector(it) } +
            input.categorical.filter { it in categoricalIndex }.map { FeatureGroup.Categorical(it) } +
            input.numeric.keys.filter { it in numericIndex }.map { FeatureGroup.Numeric(it) }

    companion object {
        /** Categorical features seen in at least this many graded works; rarer ones cannot generalise. */
        const val MIN_SUPPORT = 2

        fun of(rated: Collection<ItemInput>, spreads: Map<String, VectorSpread>, numbers: Map<String, NumberSpread>): FeatureLayout {
            val support = HashMap<String, Int>()
            rated.forEach { input -> input.categorical.forEach { support.merge(it, 1, Int::plus) } }
            val names = (support.filter { it.value >= MIN_SUPPORT }.keys + ItemInput.PREVIOUS_GRADES).toSortedSet()
            return FeatureLayout(names.toList(), spreads, numbers)
        }

        /** The mean and spread of every number over the catalogue's [inputs]. */
        fun numberSpreads(inputs: Collection<ItemInput>): Map<String, NumberSpread> {
            val values = HashMap<String, MutableList<Float>>()
            inputs.forEach { input -> input.numeric.forEach { (k, v) -> values.getOrPut(k) { ArrayList() } += v } }
            return values.filterValues { it.size >= 2 }.mapValues { (_, list) ->
                val mean = list.average()
                val sd = sqrt(list.sumOf { (it - mean) * (it - mean) } / list.size).coerceAtLeast(1e-6)
                NumberSpread(mean.toFloat(), sd.toFloat())
            }
        }
    }
}
