package io.github.vlsergey.recommend4me.onnx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * AN ONNX GRAPH MADE IN CODE, written out as the bytes of a ModelProto ([Onnx.session]): a
 * computation of the application's own — a loss and its gradient — run by ONNX Runtime on the card,
 * where no model file carries it. Only what such a graph needs of the format: nodes of the standard
 * operators with their attributes, typed inputs and outputs of named or fixed dimensions, constant
 * tensors. The field numbers are those of onnx.proto.
 */
class OnnxGraph(private val name: String) {
    private val nodes = ArrayList<ByteArray>()
    private val inputs = ArrayList<ByteArray>()
    private val outputs = ArrayList<ByteArray>()
    private val constants = ArrayList<ByteArray>()

    /** An input of the graph; a dimension is a number or the name of one the run gives. */
    fun input(name: String, type: Int, vararg dims: Any) = apply { inputs += valueInfo(name, type, dims) }

    fun output(name: String, type: Int, vararg dims: Any) = apply { outputs += valueInfo(name, type, dims) }

    /** A tensor of floats known when the graph is made. */
    fun constant(name: String, values: FloatArray, vararg dims: Long) = apply {
        val raw = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).also { b -> values.forEach { b.putFloat(it) } }.array()
        constants += tensor(name, FLOAT, dims, raw)
    }

    /** A tensor of floats known when the graph is made, read from a buffer — a matrix of data, without a copy of it on the heap first. */
    fun constant(name: String, values: FloatBuffer, vararg dims: Long) = apply {
        val raw = ByteBuffer.allocate(values.remaining() * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        raw.asFloatBuffer().put(values.duplicate())
        constants += tensor(name, FLOAT, dims, raw.array())
    }

    /** A tensor of 64-bit integers known when the graph is made. */
    fun constant(name: String, values: LongArray, vararg dims: Long) = apply {
        val raw = ByteBuffer.allocate(values.size * Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).also { b -> values.forEach { b.putLong(it) } }.array()
        constants += tensor(name, INT64, dims, raw)
    }

    /** A node of the standard operator [op]; an attribute is an Int, a Long, a Float or a LongArray. */
    fun node(op: String, inputs: List<String>, outputs: List<String>, attributes: Map<String, Any> = emptyMap()) = apply {
        nodes += Proto().apply {
            inputs.forEach { string(1, it) }
            outputs.forEach { string(2, it) }
            string(3, "${op}_${nodes.size}")
            string(4, op)
            attributes.forEach { (n, v) -> message(5, attribute(n, v)) }
        }.bytes()
    }

    /** The ModelProto of the graph, of the operators of the standard set at [opset]. */
    fun bytes(opset: Int): ByteArray {
        val graph = Proto().apply {
            nodes.forEach { message(1, it) }
            string(2, name)
            constants.forEach { message(5, it) }
            inputs.forEach { message(11, it) }
            outputs.forEach { message(12, it) }
        }.bytes()
        return Proto().apply {
            varint(1, IR_VERSION)
            string(2, "recommend4me")
            message(7, graph)
            message(8, Proto().apply { varint(2, opset.toLong()) }.bytes())
        }.bytes()
    }

    private fun valueInfo(name: String, type: Int, dims: Array<out Any>): ByteArray {
        val shape = Proto().apply {
            dims.forEach { d ->
                message(1, Proto().apply { if (d is String) string(2, d) else varint(1, (d as Number).toLong()) }.bytes())
            }
        }.bytes()
        val tensorType = Proto().apply { varint(1, type.toLong()); message(2, shape) }.bytes()
        return Proto().apply { string(1, name); message(2, Proto().apply { message(1, tensorType) }.bytes()) }.bytes()
    }

    private fun tensor(name: String, type: Int, dims: LongArray, raw: ByteArray): ByteArray = Proto().apply {
        dims.forEach { varint(1, it) }
        varint(2, type.toLong())
        string(8, name)
        bytes(9, raw)
    }.bytes()

    private fun attribute(name: String, value: Any): ByteArray = Proto().apply {
        string(1, name)
        when (value) {
            is Float -> { fixed32(2, value.toRawBits()); varint(20, ATTRIBUTE_FLOAT) }
            is Int -> { varint(3, value.toLong()); varint(20, ATTRIBUTE_INT) }
            is Long -> { varint(3, value); varint(20, ATTRIBUTE_INT) }
            is LongArray -> { value.forEach { varint(8, it) }; varint(20, ATTRIBUTE_INTS) }
            else -> error("An attribute of ${value.javaClass}")
        }
    }.bytes()

    /** The protocol buffers' wire format: a field is its number and wire type, then its value. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        private fun raw(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        fun varint(field: Int, v: Long) { raw((field shl 3).toLong()); raw(v) }

        fun fixed32(field: Int, bits: Int) {
            raw(((field shl 3) or 5).toLong())
            for (k in 0 until 4) out.write((bits ushr (8 * k)) and 0xFF)
        }

        fun bytes(field: Int, b: ByteArray) {
            raw(((field shl 3) or 2).toLong())
            raw(b.size.toLong())
            out.write(b)
        }

        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))

        fun message(field: Int, b: ByteArray) = bytes(field, b)

        fun bytes(): ByteArray = out.toByteArray()
    }

    companion object {
        /** TensorProto.DataType */
        const val FLOAT = 1
        const val INT64 = 7
        const val DOUBLE = 11

        /** The version of the format written: the one of the operator sets ONNX Runtime 1.20 reads. */
        private const val IR_VERSION = 8L

        /** AttributeProto.AttributeType */
        private const val ATTRIBUTE_FLOAT = 1L
        private const val ATTRIBUTE_INT = 2L
        private const val ATTRIBUTE_INTS = 7L
    }
}
