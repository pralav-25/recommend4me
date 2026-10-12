package io.github.vlsergey.recommend4me.encoder

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import java.nio.LongBuffer
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * A STRONG MULTILINGUAL SENTENCE ENCODER: BAAI/bge-m3 (XLM-RoBERTa large, MIT) as an ONNX graph in
 * half precision, its dense vector — the hidden state of the first piece, of unit length — 1024
 * numbers. The sibling of the reranker (bge-reranker-v2-m3): what it finds near a line is what
 * that one reads. It replaced multilingual-e5-small, whose 384 numbers found "a detective in
 * Victorian London" in the texts naming a Victor and London.
 *
 * No prefixes: bge-m3 reads a query and a passage alike.
 *
 * TEXTS ARE READ IN WINDOWS of [WINDOW] pieces, the positions XLM-RoBERTa was pretrained at: every
 * window is encoded on its own — a passage the search finds, a scene rather than a chapter — and a
 * text's vector is the windows' mean weighted by length, made unit again, of [MAX_WINDOWS] at most:
 * the 8192 pieces bge-m3 was trained to read of one text.
 */
class BgeM3Encoder(private val onnx: Onnx) : TextEncoder {

    private val tokenizer: HuggingFaceTokenizer by lazy {
        val p = onnx.path(TOKENIZER)
        check(Files.isRegularFile(p)) { "The bge-m3 tokenizer is missing: ${p.toAbsolutePath()}" }
        HuggingFaceTokenizer.newInstance(p, mapOf("addSpecialTokens" to "false", "truncation" to "false", "padding" to "false"))
    }

    /** The ids that open and close every window: <s> and </s> of XLM-RoBERTa. */
    private val bos: Long by lazy { tokenizer.encode("", true, false).ids.first() }
    private val eos: Long by lazy { tokenizer.encode("", true, false).ids.last() }

    override val id = ENCODER
    override val dim = DIM

    override fun ready(): Boolean = onnx.has(MODEL) && onnx.has(TOKENIZER)

    /** Token ids of the whole text, without special tokens. */
    fun pieces(text: String): LongArray = tokenizer.encode(text, false, false).ids

    /** Vectors of texts of any length, unit length; an empty text gets null. A query and a passage are read alike. */
    override fun encode(texts: List<String>, kind: TextKind): List<FloatArray?> {
        val (windows, owner) = windowsOf(texts, MAX_WINDOWS)
        val vectors = embedIds(windows)
        val sums = arrayOfNulls<FloatArray>(texts.size)
        windows.indices.forEach { w ->
            val sum = sums[owner[w]] ?: FloatArray(DIM).also { sums[owner[w]] = it }
            val weight = (windows[w].size - 2).toFloat()
            val v = vectors[w]
            for (k in 0 until DIM) sum[k] += weight * v[k]
        }
        return sums.map { it?.let(::unit) }
    }

    /** A vector for every window of every text, however many windows a text has. */
    override fun encodeWindows(texts: List<String>): List<List<FloatArray>> {
        val (windows, owner) = windowsOf(texts, Int.MAX_VALUE)
        val vectors = embedIds(windows)
        val out = List(texts.size) { ArrayList<FloatArray>() }
        windows.indices.forEach { w -> out[owner[w]] += vectors[w] }
        return out
    }

    /** The windows of [encodeWindows] in the text, by the characters of their first and last pieces. */
    override fun windowSpans(text: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        val spans = tokenizer.encode(text, false, false).charTokenSpans
        return spans.indices.chunked(ROOM).mapNotNull { chunk ->
            // A piece of no characters of its own (a joined byte) has no span: the window's ends are its pieces that have
            val from = chunk.firstNotNullOfOrNull { spans[it] }?.start ?: return@mapNotNull null
            val to = chunk.asReversed().firstNotNullOfOrNull { spans[it] }?.end ?: return@mapNotNull null
            from until to
        }
    }

    /**
     * Every window of every text in one list, so windows of different texts share batches, with
     * the text each is of; at most [limit] windows of a text.
     */
    private fun windowsOf(texts: List<String>, limit: Int): Pair<List<LongArray>, List<Int>> {
        val windows = ArrayList<LongArray>()
        val owner = ArrayList<Int>()
        texts.forEachIndexed { t, text ->
            if (text.isBlank()) return@forEachIndexed
            pieces(text).asList().chunked(ROOM).take(limit).forEach { chunk ->
                windows += longArrayOf(bos) + chunk.toLongArray() + longArrayOf(eos)
                owner += t
            }
        }
        return windows to owner
    }

    /**
     * Vectors of ready windows. Windows are SORTED BY LENGTH before batching: a batch is padded to
     * its longest window, and a ten-piece genre beside a five-hundred-piece description would run
     * through fifty times the work.
     */
    private fun embedIds(windows: List<LongArray>): List<FloatArray> {
        val out = arrayOfNulls<FloatArray>(windows.size)
        val order = windows.indices.sortedBy { windows[it].size }
        for (batch in order.chunked(if (onnx.onGpu(MODEL)) GPU_BATCH else BATCH)) {
            val len = batch.maxOf { windows[it].size }
            val ids = LongArray(batch.size * len) { PAD }
            val mask = LongArray(batch.size * len)
            batch.forEachIndexed { r, w ->
                windows[w].forEachIndexed { j, id ->
                    ids[r * len + j] = id
                    mask[r * len + j] = 1
                }
            }
            val shape = longArrayOf(batch.size.toLong(), len.toLong())
            val inputs = mapOf(
                "input_ids" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(ids), shape),
                "attention_mask" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(mask), shape),
            )
            val hidden = onnx.runSequence(MODEL, inputs)
            // The dense vector of bge-m3: the first piece's hidden state, as it is trained
            batch.forEachIndexed { r, w -> out[w] = unit(hidden[r][0].copyOf()) }
        }
        return out.map { it!! }
    }

    private fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        n = sqrt(n)
        return if (n > 1e-9) FloatArray(v.size) { (v[it] / n).toFloat() } else v
    }

    companion object {
        /** Stored with every vector: vectors of another encoder are of another space and are made again. */
        const val ENCODER = "bge-m3"
        const val MODEL = "bge-m3-fp16.onnx"
        const val TOKENIZER = "bge-m3-tokenizer.json"
        const val DIM = 1024
        private const val PAD = 1L

        /** The pieces of a window, its two special ones among them: the positions XLM-RoBERTa was pretrained at. */
        private const val WINDOW = 512

        /** The pieces of the text a window holds besides its two special ones. */
        private const val ROOM = WINDOW - 2

        /** The windows of one text's vector at most: the 8192 pieces bge-m3 reads of one text. */
        private const val MAX_WINDOWS = 8192 / WINDOW

        /** Windows per run, on the processor and on the card: memory, not meaning. */
        private const val BATCH = 8
        private const val GPU_BATCH = 64
    }
}
