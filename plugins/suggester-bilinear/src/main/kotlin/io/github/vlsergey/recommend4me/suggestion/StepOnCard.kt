package io.github.vlsergey.recommend4me.suggestion

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxGraph
import org.slf4j.LoggerFactory
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(StepOnCard::class.java)

/**
 * A STEP OF THE FITTING ON THE CARD ([Bilinear]): the data of the task — the items' features X, the
 * values' Z, the names' texts H and how the names fall into groups — put on the card once, among
 * the constants of two graphs of ONNX Runtime, and kept there for the whole fitting; a step sends
 * the weights there and takes the gradient back. Forward, of the weights A, B, E and the biases:
 *
 *     U = X A,  V = Z B + E,  Uh = H Σ_b s_b A_b + A_const + Σ_b A_mark(b)
 *
 * and of every group of names, its names' coordinates against its values': the scores, every pair's
 * logistic loss and slope, carried back to the names and to the values ([NamesLoss]). The pairs of
 * the items and their values — the user's answers, thousands, not millions — are taken on the
 * processor between the two runs: there a fold's items are left out of its fitting. Backward, of
 * the items' and the values' slopes: Xᵀ dU and Zᵀ dV.
 *
 * In single precision without TF32, the loss summed in double: the optimizer goes by it.
 */
internal class StepOnCard private constructor(
    private val onnx: Onnx,
    private val forward: OrtSession,
    private val backward: OrtSession,
    private val p: Problem,
    private val rank: Int,
) : AutoCloseable {
    private val n = p.n
    private val m = p.m
    private val dx = p.design.itemWidth
    private val dz = p.design.valueWidth

    /** The values of every group, one after another: where the rows of the groups' outputs go. */
    private val groupValues: IntArray = p.hintGroups.flatMap { it.values.asIterable() }.toIntArray()

    /** One run on the card at a time: the folds share the card, and a large universe's scores take a gigabyte there. */
    private val lock = ReentrantLock()

    /** What the forward run gives: the items' and the values' coordinates, and the names' loss with its gradient. */
    class Forward(
        val u: FloatArray, val v: FloatArray,
        val loss: Float, val slope: Float, val own: Float,
        /** The names' slopes carried to the values' coordinates (m × rank) and to their biases (m). */
        val dv: FloatArray, val dBias: FloatArray,
        /** To the text blocks' weights: Hᵀ dUh (hintDim × rank), and to the constant's and the marks': Σ dUh (rank). */
        val dSum: FloatArray, val dBase: FloatArray,
    )

    fun forward(theta: FloatArray, size: Int, named: Float, scale: Float): Forward = lock.withLock {
        val bAt = dx * rank
        val eAt = bAt + dz * rank
        val biasAt = eAt + m * rank
        val env = onnx.env
        fun part(at: Int, count: Int, vararg dims: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(theta, at, count).slice(), dims)
        fun scalar(x: Float) = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(x)), longArrayOf())
        val inputs = mapOf(
            "A" to part(0, dx * rank, dx.toLong(), rank.toLong()),
            "B" to part(bAt, dz * rank, dz.toLong(), rank.toLong()),
            "E" to part(eAt, m * rank, m.toLong(), rank.toLong()),
            "bias" to part(biasAt, m, m.toLong()),
            "common" to scalar(theta[size - 1]),
            "named" to scalar(named),
            "scale" to scalar(scale),
        )
        try {
            forward.run(inputs).use { r ->
                fun floats(name: String): FloatArray {
                    val b = (r.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(b.remaining()).also { b.get(it) }
                }
                // The groups' rows back to the values they are of: a character of two universes has two
                val dv = FloatArray(m * rank)
                val dBias = FloatArray(m)
                val groups = floats("dVg")
                val columns = floats("columns")
                groupValues.forEachIndexed { t, value ->
                    dBias[value] += columns[t]
                    for (q in 0 until rank) dv[value * rank + q] += groups[t * rank + q]
                }
                Forward(
                    floats("U"), floats("V"),
                    // Σ D: the sum of its columns' sums
                    (r.get("loss").get() as OnnxTensor).doubleBuffer.get(0).toFloat(), columns.sum(), floats("own_slope")[0],
                    dv, dBias, floats("dSum"), floats("dBase"),
                )
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Xᵀ dU and Zᵀ dV: the items' and the values' slopes carried to the weights of their features. */
    fun backward(du: FloatArray, dv: FloatArray): Pair<FloatArray, FloatArray> = lock.withLock {
        val env = onnx.env
        val inputs = mapOf(
            "dU" to OnnxTensor.createTensor(env, FloatBuffer.wrap(du), longArrayOf(n.toLong(), rank.toLong())),
            "dV" to OnnxTensor.createTensor(env, FloatBuffer.wrap(dv), longArrayOf(m.toLong(), rank.toLong())),
        )
        try {
            backward.run(inputs).use { r ->
                fun floats(name: String): FloatArray {
                    val b = (r.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(b.remaining()).also { b.get(it) }
                }
                floats("dA") to floats("dB")
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() {
        forward.close()
        backward.close()
    }

    companion object {
        /** The standard operators of ONNX 1.12: the Slice and the ReduceSum with their axes as inputs, the Shape with its start and end. */
        private const val OPSET = 17

        /** The step of [p]'s fitting at [rank] on the card; null without a card, or for a task without names. */
        fun open(onnx: Onnx, p: Problem, rank: Int): StepOnCard? {
            if (p.hints.isEmpty()) return null
            val started = System.currentTimeMillis()
            val forwardBytes = forwardGraph(p, rank)
            val backwardBytes = backwardGraph(p)
            val made = System.currentTimeMillis()
            val forward = onnx.onCard(forwardBytes) ?: return null
            val backward = onnx.onCard(backwardBytes) ?: return null.also { forward.close() }
            log.info(
                "the task on the card: graphs of {} MB made in {} ms, opened in {} ms",
                (forwardBytes.size + backwardBytes.size) / (1 shl 20), made - started, System.currentTimeMillis() - made,
            )
            return StepOnCard(onnx, forward, backward, p, rank)
        }

        private fun forwardGraph(p: Problem, rank: Int): ByteArray {
            val f = OnnxGraph.FLOAT
            val dx = p.design.itemWidth.toLong()
            val g = OnnxGraph("suggester-step-forward")
                .input("A", f, dx, rank.toLong()).input("B", f, p.design.valueWidth.toLong(), rank.toLong())
                .input("E", f, p.m.toLong(), rank.toLong()).input("bias", f, p.m.toLong())
                .input("common", f).input("named", f).input("scale", f)
                .constant("X", p.x.held.buffer(0, p.n * p.design.itemWidth), p.n.toLong(), dx)
                .constant("Z", p.z.held.buffer(0, p.m * p.design.valueWidth), p.m.toLong(), p.design.valueWidth.toLong())
                .constant("H", p.hintText.held.buffer(0, p.hints.size * p.hintDim), p.hints.size.toLong(), p.hintDim.toLong())
                .constant("one", floatArrayOf(1f))
                .constant("first", longArrayOf(0), 1)
                .constant("second", longArrayOf(1), 1)
            // The coordinates of the items and of the values
            g.node("MatMul", listOf("X", "A"), listOf("U"))
                .node("MatMul", listOf("Z", "B"), listOf("ZB"))
                .node("Add", listOf("ZB", "E"), listOf("V"))
            // Of the names, read as a work's every text block: the blocks' weights summed by their scales,
            // the constant's and the blocks' marks added
            val marks = longArrayOf(0) + p.hintBlocks.map { p.design.blockOffsets[it].toLong() }
            g.constant("marks", marks, marks.size.toLong())
                .node("Gather", listOf("A", "marks"), listOf("markRows"), mapOf("axis" to 0))
                .node("ReduceSum", listOf("markRows", "first"), listOf("base"), mapOf("keepdims" to 0))
            val scaled = p.hintBlocks.mapIndexed { k, b ->
                val from = p.design.blockOffsets[b] + 1L
                g.constant("from$k", longArrayOf(from), 1).constant("to$k", longArrayOf(from + p.hintDim), 1)
                    .constant("scale$k", floatArrayOf(p.design.blocks[b].scale))
                    .node("Slice", listOf("A", "from$k", "to$k", "first"), listOf("block$k"))
                    .node("Mul", listOf("block$k", "scale$k"), listOf("scaled$k"))
                "scaled$k"
            }
            g.node("Sum", scaled, listOf("aSum"))
                .node("MatMul", listOf("H", "aSum"), listOf("HA"))
                .node("Add", listOf("HA", "base"), listOf("Uh"))
            // Every group of names against its values
            val losses = ArrayList<String>()
            val owns = ArrayList<String>()
            val columns = ArrayList<String>()
            val dUs = ArrayList<String>()
            val dVs = ArrayList<String>()
            p.hintGroups.forEachIndexed { k, group ->
                // Every name of the group's nodes under one prefix of its own
                val prefix = "group${k}_"
                fun x(name: String) = prefix + name
                g.constant(x("rows"), LongArray(group.rows.size) { group.rows[it].toLong() }, group.rows.size.toLong())
                    .constant(x("values"), LongArray(group.values.size) { group.values[it].toLong() }, group.values.size.toLong())
                    .constant(x("own"), LongArray(group.positive.size) { group.positive[it].toLong() }, group.positive.size.toLong())
                    .node("Gather", listOf("Uh", x("rows")), listOf(x("Ug")), mapOf("axis" to 0))
                    .node("Gather", listOf("V", x("values")), listOf(x("Vg")), mapOf("axis" to 0))
                    .node("Gather", listOf("bias", x("values")), listOf(x("biasG")), mapOf("axis" to 0))
                    .node("Add", listOf(x("biasG"), "common"), listOf(x("shift")))
                NamesLoss.add(g, prefix, x("Ug"), x("Vg"), x("shift"), x("own"), "named", "scale")
                losses += x("loss"); owns += x("own_slope"); columns += x("columns"); dUs += x("dU"); dVs += x("dV")
            }
            // The names' rows of every group back in the order of the names
            val order = p.hintGroups.flatMap { it.rows.asIterable() }
            val inverse = LongArray(order.size).also { inv -> order.forEachIndexed { t, row -> inv[row] = t.toLong() } }
            return g.constant("inverse", inverse, inverse.size.toLong())
                .node("Concat", dUs, listOf("dUgroups"), mapOf("axis" to 0))
                .node("Gather", listOf("dUgroups", "inverse"), listOf("dUh"), mapOf("axis" to 0))
                .node("Gemm", listOf("H", "dUh"), listOf("dSum"), mapOf("transA" to 1))
                .node("ReduceSum", listOf("dUh", "first"), listOf("dBase"), mapOf("keepdims" to 0))
                .node("Sum", losses, listOf("loss"))
                .node("Sum", owns, listOf("own_slope"))
                .node("Concat", columns, listOf("columns"), mapOf("axis" to 0))
                .node("Concat", dVs, listOf("dVg"), mapOf("axis" to 0))
                .output("U", f, p.n.toLong(), rank.toLong()).output("V", f, p.m.toLong(), rank.toLong())
                .output("loss", OnnxGraph.DOUBLE).output("own_slope", f)
                .output("columns", f, "groupValues").output("dVg", f, "groupValues", rank.toLong())
                .output("dSum", f, p.hintDim.toLong(), rank.toLong()).output("dBase", f, rank.toLong())
                .bytes(OPSET)
        }

        private fun backwardGraph(p: Problem): ByteArray {
            val f = OnnxGraph.FLOAT
            return OnnxGraph("suggester-step-backward")
                .input("dU", f, p.n.toLong(), "rank").input("dV", f, p.m.toLong(), "rank")
                .constant("X", p.x.held.buffer(0, p.n * p.design.itemWidth), p.n.toLong(), p.design.itemWidth.toLong())
                .constant("Z", p.z.held.buffer(0, p.m * p.design.valueWidth), p.m.toLong(), p.design.valueWidth.toLong())
                .node("Gemm", listOf("X", "dU"), listOf("dA"), mapOf("transA" to 1))
                .node("Gemm", listOf("Z", "dV"), listOf("dB"), mapOf("transA" to 1))
                .output("dA", f, p.design.itemWidth.toLong(), "rank").output("dB", f, p.design.valueWidth.toLong(), "rank")
                .bytes(OPSET)
        }
    }
}

/**
 * The loss of a group of names against its values, as nodes of a graph: of the names' coordinates
 * U (k × rank) and the values' V (c × rank), the values' shift and every name's own value,
 *
 *     S = U Vᵀ + shift,  every name's own score S_own + named
 *     loss = Σ softplus(S) − Σ softplus(S_own) + Σ softplus(S_own + named) − Σ (S_own + named)
 *     D = σ(S)·scale, every name's own slope (σ(S_own + named) − 1)·scale
 *
 * and out, under the [prefix]: the loss (in double), Σ D_own, the sums of D's columns (Σ D their sum), D V and
 * Dᵀ U. A name's own value is one of its row: it is set right apart, a column of k numbers, and the
 * millions of the rest go through the card once a pass — not a one-hot of them all times the scores.
 */
internal object NamesLoss {
    fun add(g: OnnxGraph, prefix: String, u: String, v: String, shift: String, own: String, named: String, scale: String) {
        fun x(name: String) = "$prefix$name"
        g.node("Gemm", listOf(u, v), listOf(x("UV")), mapOf("transB" to 1))
            .node("Add", listOf(x("UV"), shift), listOf(x("S")))
            // Every name's own score, and as its text names it
            .node("Unsqueeze", listOf(own, "second"), listOf(x("ownColumn")))
            .node("GatherElements", listOf(x("S"), x("ownColumn")), listOf(x("Sown")), mapOf("axis" to 1))
            .node("Add", listOf(x("Sown"), named), listOf(x("Snamed")))
            // The loss: the rows of softplus(S) summed in single precision, then the rows in double —
            // millions of terms in an order of the card's own, every run another: a float sum of them
            // all would shake by more than the fall the optimizer waits to see settle
            .node("Softplus", listOf(x("S")), listOf(x("soft")))
            .node("ReduceSum", listOf(x("soft"), "second"), listOf(x("rowSoft")), mapOf("keepdims" to 1))
            .node("Softplus", listOf(x("Sown")), listOf(x("softOwn")))
            .node("Softplus", listOf(x("Snamed")), listOf(x("softNamed")))
            .node("Sub", listOf(x("rowSoft"), x("softOwn")), listOf(x("rowLess")))
            .node("Add", listOf(x("rowLess"), x("softNamed")), listOf(x("rowMore")))
            .node("Sub", listOf(x("rowMore"), x("Snamed")), listOf(x("rowLoss")))
            .node("Cast", listOf(x("rowLoss")), listOf(x("rowLoss64")), mapOf("to" to OnnxGraph.DOUBLE))
            .node("ReduceSum", listOf(x("rowLoss64")), listOf(x("loss")), mapOf("keepdims" to 0))
            // The slopes: σ(S)·scale, every name's own put right
            .node("Sigmoid", listOf(x("S")), listOf(x("sigma")))
            .node("Mul", listOf(x("sigma"), scale), listOf(x("D0")))
            .node("Sigmoid", listOf(x("Snamed")), listOf(x("sigmaNamed")))
            .node("Sub", listOf(x("sigmaNamed"), "one"), listOf(x("ownLess")))
            .node("Mul", listOf(x("ownLess"), scale), listOf(x("Down")))
            .node("ScatterElements", listOf(x("D0"), x("ownColumn"), x("Down")), listOf(x("D")), mapOf("axis" to 1))
            .node("ReduceSum", listOf(x("Down")), listOf(x("own_slope")), mapOf("keepdims" to 0))
            .node("ReduceSum", listOf(x("D"), "first"), listOf(x("columns")), mapOf("keepdims" to 0))
            // Carried back to the coordinates of the names and of the values
            .node("MatMul", listOf(x("D"), v), listOf(x("dU")))
            .node("Gemm", listOf(x("D"), u), listOf(x("dV")), mapOf("transA" to 1))
    }
}
