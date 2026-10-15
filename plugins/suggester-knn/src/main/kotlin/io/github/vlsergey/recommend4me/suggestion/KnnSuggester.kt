package io.github.vlsergey.recommend4me.suggestion

import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxGraph
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

private val log = LoggerFactory.getLogger(KnnSuggester::class.java)

/**
 * THE NEAREST WORKS: the chance a work has a value is the share of its [k] most alike works that
 * have it — each by how alike — drawn towards the value's share among all the works by one work's
 * worth. Alike by the cosine of what the work's texts and pictures were encoded as ([InputBlock]):
 * every block of unit length, the blocks side by side, put down to [DIMENSIONS] dimensions by a
 * random projection (fixed by its seed, the same every time) and of unit length again. Reads
 * neither the names of the values nor the work's other facets: a measure of what the texts and
 * pictures tell by likeness alone.
 *
 * The likeness of every work to every other, its [MAX_K] nearest kept: on the card when there is one
 * (a product of every work with every other, and the nearest of each, in one graph), else by
 * OpenBLAS. [k] chosen by the held-out loss of the five folds ([SuggestionPairs]), the neighbours of
 * a held-out work taken from the other folds; the one-standard-error rule preferring the more.
 * The neighbours are the task's own: nothing is packed but [k].
 */
class KnnSuggester internal constructor(private val onnx: Onnx?) : FacetSuggester {
    constructor() : this(null)

    override val id = "knn-1"

    override fun fit(task: SuggestionTask, previous: FittedSuggester?, stop: () -> Boolean): FittedSuggester? {
        if (task.valueCount == 0 || task.assigned.all { it.isEmpty() }) return null
        val started = System.currentTimeMillis()
        val pairs = SuggestionPairs(task)
        val near = Neighbours.of(task, onnx)
        val searched = System.currentTimeMillis() - started
        val tried = ArrayList<Tried>()
        for (k in KS) {
            if (stop()) throw CancellationException("the nearest works given up for newer changes")
            val at = System.currentTimeMillis()
            val held = pairs.heldOut { fold, items ->
                val prior = Prior(pairs) { pairs.foldOf[it] != fold }
                items.map { i -> chances(pairs, near, prior, i, k) { j -> pairs.foldOf[j] != fold } }
            }
            tried += Tried(k, held, System.currentTimeMillis() - at)
        }
        val chosen = oneStandardError(tried, { it.held }, compareByDescending { it.k })
        log.info(
            "{} items × {} values, {} blocks of input, neighbours {} in {} ms: {}; chosen k={} in {} ms",
            task.itemCount, task.valueCount, task.inputs.size, if (near.onCard) "on the card" else "on the processor", searched,
            tried.joinToString("; ") { "k=${it.k}: %.4f ± %.4f".format(it.held.mean, it.held.error) },
            chosen.k, System.currentTimeMillis() - started,
        )
        val report = FitReport(
            mapOf(K to chosen.k.toString()), chosen.held.mean, chosen.held.error,
            tried.map { FitTry(mapOf(K to it.k.toString()), it.held.mean, it.held.error, it.millis) }, fromScratch = true,
        )
        return Fitted(chosen.k, onnx, report)
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != FORMAT) return null
        return Fitted(input.readInt(), onnx)
    }

    private class Tried(val k: Int, val held: HeldLoss, val millis: Long)

    private class Fitted(val k: Int, val onnx: Onnx?, override val report: FitReport? = null) : FittedSuggester {
        override fun on(task: SuggestionTask): SuggestionScores {
            val pairs = SuggestionPairs(task)
            val near = Neighbours.of(task, onnx)
            val prior = Prior(pairs) { true }
            return SuggestionScores { rows -> rows.map { i -> chances(pairs, near, prior, i, k) { true } } }
        }

        override fun pack(): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT)
                out.writeInt(k)
            }
            return bytes.toByteArray()
        }
    }

    /** Of every value, its weighted share labelled 1 among the pairs of the works [counted]. */
    private class Prior(pairs: SuggestionPairs, counted: (Int) -> Boolean) {
        val share: DoubleArray

        init {
            val one = DoubleArray(pairs.m)
            val all = DoubleArray(pairs.m)
            for (i in 0 until pairs.n) {
                if (!counted(i)) continue
                pairs.active[i].forEachIndexed { j, v ->
                    all[v] += pairs.weight[i][j].toDouble()
                    one[v] += (pairs.weight[i][j] * pairs.label[i][j]).toDouble()
                }
            }
            share = DoubleArray(pairs.m) { if (all[it] > 0) one[it] / all[it] else 0.0 }
        }
    }

    /**
     * Every work's nearest works by the cosine of its texts and pictures, the nearest first: their
     * rows and likenesses, [MAX_K] at most, the work itself left out.
     */
    private class Neighbours(val rows: Array<IntArray>, val likeness: Array<FloatArray>, val onCard: Boolean) {
        companion object {
            fun of(task: SuggestionTask, onnx: Onnx?): Neighbours {
                val n = task.itemCount
                val y = project(task)
                val wanted = minOf(MAX_K + 1, n)
                val found = onnx?.let { onCard(it, y, n, wanted) }
                val (rows, likeness) = found ?: onProcessor(y, n, wanted)
                // The work itself out: the nearest to itself, or anywhere among its equals
                val own = Array(n) { i -> rows[i].indices.filter { rows[i][it] != i }.take(MAX_K) }
                return Neighbours(
                    Array(n) { i -> IntArray(own[i].size) { rows[i][own[i][it]] } },
                    Array(n) { i -> FloatArray(own[i].size) { likeness[i][own[i][it]] } },
                    found != null,
                )
            }

            /** Every work as [DIMENSIONS] numbers of unit length: its blocks of unit length side by side, projected at random (the seed fixed). */
            private fun project(task: SuggestionTask): Floats {
                val n = task.itemCount
                val width = task.inputs.sumOf { it.vectors.cols }
                val x = Floats(n.toLong().times(width).toInt().coerceAtLeast(1))
                var at = 0
                val row = FloatArray(width.coerceAtLeast(1))
                for (i in 0 until n) {
                    row.fill(0f)
                    at = 0
                    task.inputs.forEach { b ->
                        val cols = b.vectors.cols
                        if (b.present[i]) {
                            val part = FloatArray(cols)
                            b.vectors.held.take(part, 0, b.vectors.row(i), cols)
                            val norm = sqrt(part.sumOf { (it * it).toDouble() }).toFloat()
                            if (norm > 0f) for (q in 0 until cols) row[at + q] = part[q] / norm
                        }
                        at += cols
                    }
                    x.put(row, 0, i * width, width)
                }
                val random = Random(SEED)
                val r = Floats.of(FloatArray(width * DIMENSIONS) { (random.nextGaussian() / sqrt(DIMENSIONS.toDouble())).toFloat() })
                val y = Floats(n * DIMENSIONS)
                if (width > 0) Blas.product(x, width, n, width, r, DIMENSIONS, y)
                val out = FloatArray(DIMENSIONS)
                for (i in 0 until n) {
                    y.take(out, 0, i * DIMENSIONS, DIMENSIONS)
                    val norm = sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
                    if (norm > 0f) for (q in 0 until DIMENSIONS) out[q] /= norm
                    y.put(out, 0, i * DIMENSIONS, DIMENSIONS)
                }
                return y
            }

            /** The nearest of every work by a graph on the card: a chunk of works against every work, the [wanted] likeliest of each; null without a card. */
            private fun onCard(onnx: Onnx, y: Floats, n: Int, wanted: Int): Pair<Array<IntArray>, Array<FloatArray>>? {
                val f = OnnxGraph.FLOAT
                val graph = OnnxGraph("knn-nearest")
                    .input("Q", f, "rows", DIMENSIONS)
                    .supplied("Y", n.toLong(), DIMENSIONS.toLong())
                    .constant("K", longArrayOf(wanted.toLong()), 1)
                    .node("Gemm", listOf("Q", "Y"), listOf("S"), mapOf("transB" to 1))
                    .node("TopK", listOf("S", "K"), listOf("likeness", "rows_found"), mapOf("axis" to 1L, "largest" to 1L, "sorted" to 1L))
                    .output("likeness", f, "rows", wanted)
                    .output("rows_found", OnnxGraph.INT64, "rows", wanted)
                    .bytes(OPSET)
                val all = OnnxTensor.createTensor(onnx.env, y.buffer(0, n * DIMENSIONS), longArrayOf(n.toLong(), DIMENSIONS.toLong()))
                val session = onnx.onCard(graph, mapOf("Y" to all)) ?: return null.also { all.close() }
                return try {
                    val rows = Array(n) { IntArray(0) }
                    val likeness = Array(n) { FloatArray(0) }
                    val chunk = FloatArray(CHUNK * DIMENSIONS)
                    var from = 0
                    while (from < n) {
                        val count = minOf(CHUNK, n - from)
                        y.take(chunk, 0, from * DIMENSIONS, count * DIMENSIONS)
                        val buffer = ByteBuffer.allocateDirect(count * DIMENSIONS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        buffer.put(chunk, 0, count * DIMENSIONS).flip()
                        OnnxTensor.createTensor(onnx.env, buffer, longArrayOf(count.toLong(), DIMENSIONS.toLong())).use { q ->
                            session.run(mapOf("Q" to q)).use { result ->
                                @Suppress("UNCHECKED_CAST")
                                val values = result.get(0).value as Array<FloatArray>
                                @Suppress("UNCHECKED_CAST")
                                val indices = result.get(1).value as Array<LongArray>
                                for (r in 0 until count) {
                                    likeness[from + r] = values[r]
                                    rows[from + r] = IntArray(indices[r].size) { indices[r][it].toInt() }
                                }
                            }
                        }
                        from += count
                    }
                    rows to likeness
                } catch (e: Exception) {
                    log.warn("the nearest works could not be found on the card, the processor finds them: {}", e.message)
                    null
                } finally {
                    session.close()
                    all.close()
                }
            }

            /** The nearest of every work by OpenBLAS: a chunk of works against every work, the [wanted] likeliest of each kept. */
            private fun onProcessor(y: Floats, n: Int, wanted: Int): Pair<Array<IntArray>, Array<FloatArray>> {
                val rows = Array(n) { IntArray(0) }
                val likeness = Array(n) { FloatArray(0) }
                // Yᵀ once: a chunk's product is then Q · Yᵀ
                val all = FloatArray(n * DIMENSIONS).also { y.take(it, 0, 0, n * DIMENSIONS) }
                val yt = Floats(DIMENSIONS * n)
                val column = FloatArray(n)
                for (q in 0 until DIMENSIONS) {
                    for (j in 0 until n) column[j] = all[j * DIMENSIONS + q]
                    yt.put(column, 0, q * n, n)
                }
                val q = Floats(CHUNK * DIMENSIONS)
                val s = Floats(CHUNK * n)
                val line = FloatArray(n)
                var from = 0
                while (from < n) {
                    val count = minOf(CHUNK, n - from)
                    q.put(all, from * DIMENSIONS, 0, count * DIMENSIONS)
                    Blas.product(q, DIMENSIONS, count, DIMENSIONS, yt, n, s)
                    for (r in 0 until count) {
                        s.take(line, 0, r * n, n)
                        val top = (0 until n).sortedByDescending { line[it] }.take(wanted)
                        rows[from + r] = top.toIntArray()
                        likeness[from + r] = FloatArray(top.size) { line[top[it]] }
                    }
                    from += count
                }
                return rows to likeness
            }
        }
    }

    companion object {
        private const val FORMAT = 1
        private const val K = "k"

        /** How many nearest works are tried; the most kept of each work. */
        private val KS = listOf(5, 10, 20, 40)
        private const val MAX_K = 48

        /** The dimensions every work is put down to, and the seed of the projection. */
        private const val DIMENSIONS = 256
        private const val SEED = 20261015L

        /** Works asked for at once. */
        private const val CHUNK = 512

        /** The standard operators of ONNX 1.12. */
        private const val OPSET = 17

        /** How much the value's share among all the works weighs against the neighbours': one work's worth. */
        private const val PRIOR = 1.0

        /**
         * The chance of every value of the work [i]: of its [k] nearest works that [usable] allows —
         * those of the other folds, of a held-out one — the weighted share labelled 1 of their pairs
         * with the value, each by its likeness, drawn towards the value's share among all by [PRIOR].
         */
        private fun chances(pairs: SuggestionPairs, near: Neighbours, prior: Prior, i: Int, k: Int, usable: (Int) -> Boolean): FloatArray {
            val one = DoubleArray(pairs.m)
            val all = DoubleArray(pairs.m)
            var taken = 0
            val rows = near.rows[i]
            for (t in rows.indices) {
                if (taken == k) break
                val j = rows[t]
                if (!usable(j)) continue
                taken++
                val s = near.likeness[i][t].coerceAtLeast(0f).toDouble()
                if (s == 0.0) continue
                pairs.active[j].forEachIndexed { q, v ->
                    val w = s * pairs.weight[j][q]
                    all[v] += w
                    one[v] += w * pairs.label[j][q]
                }
            }
            return FloatArray(pairs.m) { v -> ((one[v] + PRIOR * prior.share[v]) / (all[v] + PRIOR)).toFloat() }
        }
    }
}
