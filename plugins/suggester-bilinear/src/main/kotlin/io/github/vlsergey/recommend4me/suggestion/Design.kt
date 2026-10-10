package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/**
 * How one input block's vectors become features: scaled by √dim over the typical length of the
 * vectors — a coordinate about as large as a one-hot feature — and NOT CENTRED. An item is judged by
 * how alike it is to the items learnt from, not by how it departs from their mean: centred, an item
 * unlike every one of them comes out their opposite (a work of dragons the opposite of the works of
 * space), and the weights learnt of them say the opposite of what they say of those — with no data
 * to stop it. Uncentred, it is near nought along what was learnt, and judged by the base rates; the
 * part all texts share is learnt as a bias. By the vectors' own length, not by how far they
 * spread: a block nearly the same for every item would be stretched without end. An item without
 * the block: zeros, and its mark of having the block off. ([center] is kept nought: the layout of
 * a kept model reads the same.)
 */
class BlockScale(val name: String, val center: FloatArray, val scale: Float) {
    val dim: Int get() = center.size

    companion object {
        fun of(block: InputBlock): BlockScale? {
            val d = block.vectors.cols
            val rows = block.present.indices.filter { block.present[it] }
            if (rows.isEmpty() || d == 0) return null
            val held = block.vectors.held
            val center = FloatArray(d)
            var length = 0.0
            rows.forEach { i ->
                var s = 0.0
                for (q in 0 until d) {
                    val c = held[block.vectors.row(i) + q]
                    s += c * c
                }
                length += sqrt(s)
            }
            length /= rows.size
            return BlockScale(block.name, center, if (length > 0) (sqrt(d.toDouble()) / length).toFloat() else 1f)
        }
    }
}

/**
 * THE FEATURES OF THE ITEMS AND OF THE VALUES, laid out as on the task the model was fitted on, so
 * that a later task of the same facet — new items, new values, a block an item gained — is read
 * the same way.
 *
 * An item: a constant; for every input block, whether the item has it and its centred and scaled
 * vector; a one-hot of every value of its other facets the fitted task knew.
 *
 * A value: a constant; whether its name is encoded and the name's centred and scaled vector; a
 * one in every group of it in every grouping — a pairing has two, one for each of its characters.
 * What is learnt of a value beyond its name — its own vector and bias — is kept by its key
 * ([valueKeys]).
 */
class Design(
    val blocks: List<BlockScale>,
    val contextKeys: List<String>,
    val names: BlockScale?,
    val nameDim: Int,
    /** Every grouping by its name, with the keys of its groups: a column each. */
    val groupings: List<Pair<String, List<String>>>,
    val valueKeys: List<String>,
) {
    private val contextIndex = contextKeys.withIndex().associate { (i, k) -> k to i }
    internal val valueIndex = valueKeys.withIndex().associate { (i, k) -> k to i }
    private val groupIndex = groupings.map { (_, keys) -> keys.withIndex().associate { (i, k) -> k to i } }

    val itemWidth: Int = 1 + blocks.sumOf { 1 + it.dim } + contextKeys.size

    /** Where each block's mark of having it lies among an item's features; its vector follows the mark. */
    val blockOffsets: List<Int> = blocks.runningFold(1) { at, b -> at + 1 + b.dim }.dropLast(1)

    /** The blocks a text of a work is read in — the title, the description, the notes, the site's lines — of vectors of [dim]: indices of [blocks]. */
    fun textBlocks(dim: Int): List<Int> = blocks.indices.filter { blocks[it].name.startsWith("text:") && blocks[it].dim == dim }
    val valueWidth: Int = 2 + nameDim + groupings.sumOf { it.second.size }

    /** Whether the task can be read by this layout: its blocks of the same names and its names of the same length. */
    fun reads(task: SuggestionTask): Boolean {
        val dims = blocks.associate { it.name to it.dim }
        return task.values.cols == nameDim && task.inputs.all { b -> dims[b.name]?.let { it == b.vectors.cols } ?: true }
    }

    /** The features of the [rows] of the task, a row each. */
    fun items(task: SuggestionTask, rows: IntArray): Matrix {
        val out = Matrix(rows.size, itemWidth)
        val held = out.held
        val byName = task.inputs.associateBy { it.name }
        // The task's context keys as columns of this layout
        val columns = IntArray(task.contextCount) { contextIndex[task.contextKeys[it]] ?: -1 }
        val row = FloatArray(itemWidth)
        rows.forEachIndexed { r, i ->
            row.fill(0f)
            row[0] = 1f
            var at = 1
            blocks.forEach { scale ->
                val block = byName[scale.name]
                if (block != null && block.present[i]) {
                    row[at] = 1f
                    val base = block.vectors.row(i)
                    for (q in 0 until scale.dim) row[at + 1 + q] = (block.vectors.held[base + q] - scale.center[q]) * scale.scale
                }
                at += 1 + scale.dim
            }
            task.context[i].forEach { c -> columns[c].takeIf { it >= 0 }?.let { row[at + it] = 1f } }
            held.put(row, 0, out.row(r), itemWidth)
        }
        return out
    }

    /** The features of every value of the task, in its order. */
    fun values(task: SuggestionTask): Matrix {
        val m = task.valueCount
        val out = Matrix(m, valueWidth)
        val row = FloatArray(valueWidth)
        val name = FloatArray(nameDim)
        val groupingIndex = task.groupings.associateBy { it.name }
        for (v in 0 until m) {
            row.fill(0f)
            row[0] = 1f
            task.values.held.take(name, 0, task.values.row(v), nameDim)
            if (names != null && name.any { it != 0f }) {
                row[1] = 1f
                for (q in 0 until nameDim) row[2 + q] = (name[q] - names.center[q]) * names.scale
            }
            var at = 2 + nameDim
            groupings.forEachIndexed { k, (g, keys) ->
                // The task's groups by their keys: one this layout does not know has no column
                groupingIndex[g]?.let { grouping ->
                    grouping.of[v].forEach { group -> groupIndex[k][grouping.groups[group]]?.let { row[at + it] = 1f } }
                }
                at += keys.size
            }
            out.held.put(row, 0, out.row(v), valueWidth)
        }
        return out
    }

    /** The index in [valueKeys] of every value of the task; -1 for one the fitted task did not have. */
    fun valueIndices(task: SuggestionTask): IntArray = IntArray(task.valueCount) { valueIndex[task.valueKeys[it]] ?: -1 }

    /**
     * Where every feature of an item of this layout was in the [old] one, and by what its weight
     * is multiplied there to weigh the same: the constant; a block of the same name and length —
     * its mark, its coordinates by the ratio of the scales, so a feature times its weight stays as it
     * was; a value of the context by its key. -1: a feature the old layout had not.
     */
    fun itemFeaturesFrom(old: Design): Pair<IntArray, FloatArray> {
        val at = IntArray(itemWidth) { -1 }
        val factor = FloatArray(itemWidth) { 1f }
        at[0] = 0
        val oldBlocks = old.blocks.withIndex().associate { (i, b) -> b.name to i }
        blocks.forEachIndexed { i, b ->
            val j = oldBlocks[b.name]?.takeIf { old.blocks[it].dim == b.dim } ?: return@forEachIndexed
            val here = blockOffsets[i]
            val there = old.blockOffsets[j]
            at[here] = there
            for (q in 0 until b.dim) {
                at[here + 1 + q] = there + 1 + q
                factor[here + 1 + q] = old.blocks[j].scale / b.scale
            }
        }
        val base = 1 + blocks.sumOf { 1 + it.dim }
        val oldBase = 1 + old.blocks.sumOf { 1 + it.dim }
        contextKeys.forEachIndexed { c, key -> old.contextIndex[key]?.let { at[base + c] = oldBase + it } }
        return at to factor
    }

    /** As [itemFeaturesFrom], of a value's features: the constant, its name's mark and coordinates, its groups by their keys. */
    fun valueFeaturesFrom(old: Design): Pair<IntArray, FloatArray> {
        val at = IntArray(valueWidth) { -1 }
        val factor = FloatArray(valueWidth) { 1f }
        at[0] = 0
        if (names != null && old.names != null && nameDim == old.nameDim) {
            at[1] = 1
            for (q in 0 until nameDim) {
                at[2 + q] = 2 + q
                factor[2 + q] = old.names.scale / names.scale
            }
        }
        var here = 2 + nameDim
        val oldAt = HashMap<Pair<String, String>, Int>()
        var there = 2 + old.nameDim
        old.groupings.forEach { (g, keys) -> keys.forEachIndexed { k, key -> oldAt[g to key] = there + k }; there += keys.size }
        groupings.forEach { (g, keys) ->
            keys.forEachIndexed { k, key -> oldAt[g to key]?.let { at[here + k] = it } }
            here += keys.size
        }
        return at to factor
    }

    fun write(out: DataOutputStream) {
        out.writeInt(blocks.size)
        blocks.forEach { writeScale(out, it) }
        writeStrings(out, contextKeys)
        out.writeInt(nameDim)
        out.writeBoolean(names != null)
        names?.let { writeScale(out, it) }
        out.writeInt(groupings.size)
        groupings.forEach { (g, keys) -> out.writeUTF(g); writeStrings(out, keys) }
        writeStrings(out, valueKeys)
    }

    companion object {
        /** The layout of a task: its blocks some item has, its context, its values' names and groups. */
        fun of(task: SuggestionTask): Design {
            val names = BlockScale.of(InputBlock("names", task.values, BooleanArray(task.valueCount) { v ->
                (0 until task.values.cols).any { task.values.held[task.values.row(v) + it] != 0f }
            }))
            return Design(
                task.inputs.mapNotNull { BlockScale.of(it) }, task.contextKeys, names, task.values.cols,
                task.groupings.map { it.name to it.groups }, task.valueKeys,
            )
        }

        fun read(input: DataInputStream): Design {
            val blocks = List(input.readInt()) { readScale(input) }
            val context = readStrings(input)
            val nameDim = input.readInt()
            val names = if (input.readBoolean()) readScale(input) else null
            val groupings = List(input.readInt()) { input.readUTF() to readStrings(input) }
            return Design(blocks, context, names, nameDim, groupings, readStrings(input))
        }

        private fun writeScale(out: DataOutputStream, s: BlockScale) {
            out.writeUTF(s.name)
            out.writeFloat(s.scale)
            writeFloats(out, s.center)
        }

        private fun readScale(input: DataInputStream): BlockScale {
            val name = input.readUTF()
            val scale = input.readFloat()
            return BlockScale(name, readFloats(input), scale)
        }

        fun writeFloats(out: DataOutputStream, a: FloatArray) {
            out.writeInt(a.size)
            a.forEach { out.writeFloat(it) }
        }

        fun readFloats(input: DataInputStream): FloatArray = FloatArray(input.readInt()) { input.readFloat() }

        // Strings longer than writeUTF takes (65535 bytes) are not keys; written as bytes all the same
        private fun writeStrings(out: DataOutputStream, list: List<String>) {
            out.writeInt(list.size)
            list.forEach { s ->
                val bytes = s.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }

        private fun readStrings(input: DataInputStream): List<String> = List(input.readInt()) {
            val bytes = ByteArray(input.readInt())
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }
}
