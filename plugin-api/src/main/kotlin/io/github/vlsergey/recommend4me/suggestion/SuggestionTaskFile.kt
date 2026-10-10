package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A [SuggestionTask] kept in a file whole, as the application handed it to a suggester: a fitting
 * of real data, made again and measured apart from the application — a profile of it, a change
 * of the model tried on it — as often as needed, without waiting for the data to change.
 */
object SuggestionTaskFile {
    private const val FORMAT = 2

    fun write(task: SuggestionTask, file: Path) {
        Files.createDirectories(file.parent)
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(file)).buffered()).use { out ->
            out.writeInt(FORMAT)
            strings(out, task.itemIds)
            out.writeInt(task.inputs.size)
            task.inputs.forEach { b ->
                out.writeUTF(b.name)
                matrix(out, b.vectors)
                out.writeInt(b.present.size)
                b.present.forEach(out::writeBoolean)
            }
            matrix(out, task.values)
            strings(out, task.valueKeys)
            listOf(task.assigned, task.confirmed, task.rejected, task.context, task.examples).forEach { lists(out, it) }
            strings(out, task.contextKeys)
            out.writeInt(task.mentions.size)
            task.mentions.forEach { named ->
                out.writeInt(named?.size ?: -1)
                named?.forEach { (v, count) -> out.writeInt(v); out.writeInt(count) }
            }
            out.writeInt(task.allowed.size)
            task.allowed.forEach { a ->
                out.writeBoolean(a != null)
                if (a != null) ints(out, a)
            }
            out.writeInt(task.groupings.size)
            task.groupings.forEach { g ->
                out.writeUTF(g.name)
                strings(out, g.groups)
                lists(out, g.of)
            }
            out.writeDouble(task.lackWeight)
            out.writeInt(task.hints.size)
            task.hints.forEach { h ->
                out.writeInt(h.vector.size)
                h.vector.forEach(out::writeFloat)
                out.writeInt(h.value)
                ints(out, h.among)
            }
        }
    }

    fun read(file: Path): SuggestionTask = DataInputStream(GZIPInputStream(Files.newInputStream(file)).buffered()).use { input ->
        val format = input.readInt()
        check(format == FORMAT || format == FORMAT - 1) { "$file: not a task of format $FORMAT" }
        val itemIds = strings(input)
        val inputs = List(input.readInt()) {
            val name = input.readUTF()
            val vectors = matrix(input)
            InputBlock(name, vectors, BooleanArray(input.readInt()) { input.readBoolean() })
        }
        val values = matrix(input)
        val valueKeys = strings(input)
        val (assigned, confirmed, rejected, context, examples) = List(5) { lists(input) }
        val contextKeys = strings(input)
        val mentions = List(input.readInt()) {
            val size = input.readInt()
            if (size < 0) null else HashMap<Int, Int>(size).also { m -> repeat(size) { m[input.readInt()] = input.readInt() } }
        }
        val allowed = List(input.readInt()) { if (input.readBoolean()) ints(input) else null }
        val groupings = List(input.readInt()) { ValueGrouping(input.readUTF(), strings(input), lists(input)) }
        val lackWeight = input.readDouble()
        val hints = List(input.readInt()) {
            val vector = FloatArray(input.readInt()) { input.readFloat() }
            val value = input.readInt()
            // The format before it kept the rivals alone, an array of every name
            val among = ints(input).let { if (format == FORMAT) it else (it + value).distinct().sorted().toIntArray() }
            NameHint(vector, value, among)
        }
        SuggestionTask(itemIds, inputs, values, valueKeys, assigned, confirmed, rejected, context, contextKeys, mentions, allowed, groupings, lackWeight, examples, hints)
    }

    private fun strings(out: DataOutputStream, list: List<String>) {
        out.writeInt(list.size)
        list.forEach(out::writeUTF)
    }

    private fun strings(input: DataInputStream): List<String> = List(input.readInt()) { input.readUTF() }

    private fun ints(out: DataOutputStream, a: IntArray) {
        out.writeInt(a.size)
        a.forEach(out::writeInt)
    }

    private fun ints(input: DataInputStream): IntArray = IntArray(input.readInt()) { input.readInt() }

    private fun lists(out: DataOutputStream, list: List<IntArray>) {
        out.writeInt(list.size)
        list.forEach { ints(out, it) }
    }

    private fun lists(input: DataInputStream): List<IntArray> = List(input.readInt()) { ints(input) }

    private fun matrix(out: DataOutputStream, m: Matrix) {
        out.writeInt(m.rows)
        out.writeInt(m.cols)
        val row = FloatArray(m.cols)
        for (i in 0 until m.rows) {
            m.held.take(row, 0, m.row(i), m.cols)
            row.forEach(out::writeFloat)
        }
    }

    private fun matrix(input: DataInputStream): Matrix {
        val m = Matrix(input.readInt(), input.readInt())
        val row = FloatArray(m.cols)
        for (i in 0 until m.rows) {
            for (j in row.indices) row[j] = input.readFloat()
            m.held.put(row, 0, m.row(i), m.cols)
        }
        return m
    }
}
