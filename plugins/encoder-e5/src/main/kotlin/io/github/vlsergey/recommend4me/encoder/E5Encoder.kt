package io.github.vlsergey.recommend4me.encoder

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import java.nio.LongBuffer
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * A MULTILINGUAL sentence encoder: intfloat/multilingual-e5-small as an ONNX graph, 384 numbers,
 * mean-pooled and of unit length. A Russian query and an English overview land in one space —
 * the English-only MiniLM it replaced turned every Russian query into nearly the same vector,
 * and the search answered every one of them with the same games.
 *
 * E5 IS TRAINED WITH PREFIXES: "query: " for what is searched for, "passage: " for what is
 * searched in. The texts of the games are passages; the search line is a query.
 *
 * LONG TEXTS ARE READ IN WINDOWS of [WINDOW] tokens (prefix included): every window is encoded
 * on its own, and the text's vector is their mean weighted by length, made unit again.
 * Attention costs the square of the length, so windows shorter than the model's 512 are cheaper.
 */
class E5Encoder(private val onnx: Onnx) : TextEncoder {

    private val TextKind.prefix: String get() = when (this) {
        TextKind.QUERY -> "query: "
        TextKind.PASSAGE -> "passage: "
    }

    private val tokenizer: HuggingFaceTokenizer by lazy {
        val p = onnx.path(TOKENIZER)
        check(Files.isRegularFile(p)) { "The text encoder tokenizer is missing: ${p.toAbsolutePath()}" }
        HuggingFaceTokenizer.newInstance(p, mapOf("addSpecialTokens" to "false", "truncation" to "false", "padding" to "false"))
    }

    /** The ids that open and close every window: <s> and </s> of XLM-RoBERTa. */
    private val bos: Long by lazy { tokenizer.encode("", true, false).ids.first() }
    private val eos: Long by lazy { tokenizer.encode("", true, false).ids.last() }

    private val prefixIds: Map<TextKind, LongArray> by lazy { TextKind.entries.associateWith { pieces(it.prefix.trim()) } }

    override val id = ENCODER
    override val dim = DIM

    override fun ready(): Boolean = onnx.has(MODEL) && onnx.has(TOKENIZER)

    /** Token ids of the whole text, without special tokens. */
    fun pieces(text: String): LongArray = tokenizer.encode(text, false, false).ids

    /** Vectors of texts of any length, unit length; an empty text gets null. */
    override fun encode(texts: List<String>, kind: TextKind): List<FloatArray?> {
        val prefix = prefixIds.getValue(kind)
        val (windows, owner) = windowsOf(texts, prefix, MAX_WINDOWS)
        val vectors = embedIds(windows)
        val sums = arrayOfNulls<FloatArray>(texts.size)
        windows.indices.forEach { w ->
            val sum = sums[owner[w]] ?: FloatArray(DIM).also { sums[owner[w]] = it }
            val weight = (windows[w].size - 2 - prefix.size).toFloat()
            val v = vectors[w]
            for (k in 0 until DIM) sum[k] += weight * v[k]
        }
        return sums.map { it?.let(::unit) }
    }

    /** A vector for every window of every text, as passages, however many windows a text has. */
    override fun encodeWindows(texts: List<String>): List<List<FloatArray>> {
        val (windows, owner) = windowsOf(texts, prefixIds.getValue(TextKind.PASSAGE), Int.MAX_VALUE)
        val vectors = embedIds(windows)
        val out = List(texts.size) { ArrayList<FloatArray>() }
        windows.indices.forEach { w -> out[owner[w]] += vectors[w] }
        return out
    }

    /** The windows of [encodeWindows] in the text, by the characters of their first and last pieces. */
    override fun windowSpans(text: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        val spans = tokenizer.encode(text, false, false).charTokenSpans
        return spans.indices.chunked(room(prefixIds.getValue(TextKind.PASSAGE))).mapNotNull { chunk ->
            // A piece of no characters of its own (a joined byte) has no span: the window's ends are its pieces that have
            val from = chunk.firstNotNullOfOrNull { spans[it] }?.start ?: return@mapNotNull null
            val to = chunk.asReversed().firstNotNullOfOrNull { spans[it] }?.end ?: return@mapNotNull null
            from until to
        }
    }

    /** The pieces of the text a window holds besides its two special ones and the prefix. */
    private fun room(prefix: LongArray) = WINDOW - 2 - prefix.size

    /**
     * Every window of every text in one list, so windows of different texts share batches, with
     * the text each is of; at most [limit] windows of a text.
     */
    private fun windowsOf(texts: List<String>, prefix: LongArray, limit: Int): Pair<List<LongArray>, List<Int>> {
        val room = room(prefix)
        val windows = ArrayList<LongArray>()
        val owner = ArrayList<Int>()
        texts.forEachIndexed { t, text ->
            if (text.isBlank()) return@forEachIndexed
            pieces(text).asList().chunked(room).take(limit).forEach { chunk ->
                windows += longArrayOf(bos) + prefix + chunk.toLongArray() + longArrayOf(eos)
                owner += t
            }
        }
        return windows to owner
    }

    /**
     * Vectors of ready windows. Windows are SORTED BY LENGTH before batching: a batch is padded to
     * its longest window, and mixing a 10-token genre with a 250-token overview would run the
     * short one through twenty-five times the work.
     */
    fun embedIds(windows: List<LongArray>): List<FloatArray> {
        val out = arrayOfNulls<FloatArray>(windows.size)
        val order = windows.indices.sortedBy { windows[it].size }
        val wantsTypes = TOKEN_TYPES in onnx.inputNames(MODEL)
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
            val inputs = mutableMapOf(
                "input_ids" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(ids), shape),
                "attention_mask" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(mask), shape),
            )
            if (wantsTypes) inputs[TOKEN_TYPES] = OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(LongArray(ids.size)), shape)
            val hidden = onnx.runSequence(MODEL, inputs)
            // Mean pooling over the real tokens, as e5 is trained
            batch.forEachIndexed { r, w ->
                val v = FloatArray(DIM)
                val n = windows[w].size
                for (j in 0 until n) {
                    val h = hidden[r][j]
                    for (k in 0 until DIM) v[k] += h[k]
                }
                for (k in 0 until DIM) v[k] /= n
                out[w] = unit(v)
            }
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
        const val ENCODER = "multilingual-e5-small"
        const val MODEL = "e5-small.onnx"
        const val TOKENIZER = "e5-small-tokenizer.json"
        const val DIM = 384
        private const val TOKEN_TYPES = "token_type_ids"
        private const val PAD = 1L
        private const val WINDOW = 256
        private const val BATCH = 32
        private const val GPU_BATCH = 128

        /** At most this many windows per text: 16 × ~250 tokens cover any overview. */
        private const val MAX_WINDOWS = 16
    }
}
