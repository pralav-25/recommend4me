package io.github.vlsergey.recommend4me.suggestion

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxGraph
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * THE LOSS OF THE NAMES AGAINST THEIR RIVALS ON THE CARD ([Bilinear]): a group of names — a
 * universe's characters' — scored against its values, every pair's logistic loss and slope, and the
 * slopes carried back, in one run of a graph of ONNX Runtime on CUDA. A large universe is thousands
 * of names against thousands of characters, tens of millions of pairs a step of a fitting: on the
 * card a matter of milliseconds, on the processor of tens of them.
 *
 * Of a group of k names and c values, with U (k × rank) and V (c × rank) their coordinates, the
 * values' shift (their biases and the common one), each name's own value, the weight of a text's
 * naming its value and the scale of a slope:
 *
 *     S = U Vᵀ + shift + named·Y    (Y: the one-hot of every name's own value)
 *     loss = Σ softplus(S) − Y·S,   D = (σ(S) − Y)·scale
 *
 * and out: the loss, Σ D, Σ D·Y, the sums of D's columns, D V and Dᵀ U.
 */
internal class NamesOnCard(private val onnx: Onnx) {

    private val session: OrtSession by lazy { onnx.session(GRAPH, ::graph) }

    /** Whether the graph runs on the card: on the processor the plain loops of [Bilinear] are kept. */
    fun ready(): Boolean = runCatching { session }.isSuccess && onnx.opened(GRAPH) == true

    /** One group on the card at a time: the scores of a large universe take a gigabyte there. */
    private val lock = ReentrantLock()

    /** What a group adds to the loss and to the sums of the slopes. */
    class Sums(val loss: Float, val slope: Float, val own: Float)

    /**
     * The group of [k] names at [gu] against [c] values at [gv] ([rank] coordinates each), the
     * values' [shift], every name's own value among them ([positive]): the loss and the sums, the
     * sums of the slopes' columns into [columns], D V into [gdu] and Dᵀ U into [gdv].
     */
    fun run(
        gu: Floats, k: Int, gv: Floats, c: Int, rank: Int, shift: FloatArray, positive: IntArray, named: Float, scale: Float,
        columns: Floats, gdu: Floats, gdv: Floats,
    ): Sums = lock.withLock {
        val env = onnx.env
        val inputs = mapOf(
            "U" to OnnxTensor.createTensor(env, gu.buffer(0, k * rank), longArrayOf(k.toLong(), rank.toLong())),
            "V" to OnnxTensor.createTensor(env, gv.buffer(0, c * rank), longArrayOf(c.toLong(), rank.toLong())),
            "shift" to OnnxTensor.createTensor(env, FloatBuffer.wrap(shift, 0, c), longArrayOf(c.toLong())),
            "own" to OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(k) { positive[it].toLong() }), longArrayOf(k.toLong())),
            "named" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(named)), longArrayOf()),
            "scale" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(scale)), longArrayOf()),
        )
        try {
            session.run(inputs).use { result ->
                fun floats(name: String): FloatBuffer = (result.get(name).get() as OnnxTensor).floatBuffer
                fun into(name: String, to: Floats, n: Int) = FloatArray(n).also { floats(name).get(it) }.let { to.put(it, 0, 0, n) }
                into("columns", columns, c)
                into("dU", gdu, k * rank)
                into("dV", gdv, c * rank)
                val loss = (result.get("loss").get() as OnnxTensor).doubleBuffer.get(0).toFloat()
                Sums(loss, floats("slope").get(0), floats("own_slope").get(0))
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun graph(): ByteArray {
        val f = OnnxGraph.FLOAT
        return OnnxGraph(GRAPH)
            .input("U", f, "k", "rank").input("V", f, "c", "rank").input("shift", f, "c")
            .input("own", OnnxGraph.INT64, "k").input("named", f).input("scale", f)
            .constant("hot", floatArrayOf(0f, 1f), 2)
            .constant("first", longArrayOf(0), 1)
            // The scores, every name's own value marked and given the weight of a text's naming it
            .node("Gemm", listOf("U", "V"), listOf("UV"), mapOf("transB" to 1))
            .node("Add", listOf("UV", "shift"), listOf("shifted"))
            .node("Shape", listOf("V"), listOf("depth"), mapOf("start" to 0, "end" to 1))
            .node("OneHot", listOf("own", "depth", "hot"), listOf("Y"), mapOf("axis" to -1))
            .node("Mul", listOf("Y", "named"), listOf("namedY"))
            .node("Add", listOf("shifted", "namedY"), listOf("S"))
            // The loss: softplus(S) − Y·S
            .node("Softplus", listOf("S"), listOf("soft"))
            .node("Mul", listOf("Y", "S"), listOf("YS"))
            .node("Sub", listOf("soft", "YS"), listOf("L"))
            // Summed in double precision: the card adds tens of millions of terms in an order of its
            // own, every run another, and a float sum of them would shake from run to run by more than
            // the fall the optimizer waits to see settle
            .node("Cast", listOf("L"), listOf("L64"), mapOf("to" to OnnxGraph.DOUBLE))
            .node("ReduceSum", listOf("L64"), listOf("loss"), mapOf("keepdims" to 0))
            // The slopes: (σ(S) − Y)·scale
            .node("Sigmoid", listOf("S"), listOf("sigma"))
            .node("Sub", listOf("sigma", "Y"), listOf("D0"))
            .node("Mul", listOf("D0", "scale"), listOf("D"))
            .node("ReduceSum", listOf("D"), listOf("slope"), mapOf("keepdims" to 0))
            .node("Mul", listOf("D", "Y"), listOf("DY"))
            .node("ReduceSum", listOf("DY"), listOf("own_slope"), mapOf("keepdims" to 0))
            .node("ReduceSum", listOf("D", "first"), listOf("columns"), mapOf("keepdims" to 0))
            // Carried back to the coordinates of the names and of the values
            .node("MatMul", listOf("D", "V"), listOf("dU"))
            .node("Gemm", listOf("D", "U"), listOf("dV"), mapOf("transA" to 1))
            .output("loss", OnnxGraph.DOUBLE).output("slope", f).output("own_slope", f)
            .output("columns", f, "c").output("dU", f, "k", "rank").output("dV", f, "c", "rank")
            .bytes(OPSET)
    }

    companion object {
        private const val GRAPH = "suggester-names-loss"

        /** The standard operators of ONNX 1.12: the Shape with its start and end, the ReduceSum with its axes as an input. */
        private const val OPSET = 17
    }
}
