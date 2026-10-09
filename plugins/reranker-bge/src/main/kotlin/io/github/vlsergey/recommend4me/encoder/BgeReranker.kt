package io.github.vlsergey.recommend4me.encoder

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import java.nio.LongBuffer
import java.nio.file.Files

/**
 * A MULTILINGUAL CROSS-ENCODER: BAAI/bge-reranker-v2-m3 (XLM-RoBERTa large, Apache-2.0) as an ONNX
 * graph in half precision. The two texts go in as one sequence, `<s> first </s></s> second </s>`,
 * and the graph answers one number, the logit of the second answering the first.
 *
 * Each text is cut to [SIDE] tokens: a description of a work says what it is in its first lines,
 * and attention costs the square of the length. Pairs are SORTED BY LENGTH before batching, as the
 * text encoder's windows are: a batch is padded to its longest pair. On the card the batches are
 * larger ([GPU_BATCH]).
 */
class BgeReranker(private val onnx: Onnx) : TextReranker {

    private val tokenizer: HuggingFaceTokenizer by lazy {
        val p = onnx.path(TOKENIZER)
        check(Files.isRegularFile(p)) { "The reranker's tokenizer is missing: ${p.toAbsolutePath()}" }
        HuggingFaceTokenizer.newInstance(p, mapOf("addSpecialTokens" to "false", "truncation" to "false", "padding" to "false"))
    }

    /** The ids that open and close a text: <s> and </s> of XLM-RoBERTa. */
    private val bos: Long by lazy { tokenizer.encode("", true, false).ids.first() }
    private val eos: Long by lazy { tokenizer.encode("", true, false).ids.last() }

    override val id = RERANKER

    override fun ready(): Boolean = onnx.has(MODEL) && onnx.has(TOKENIZER)

    private fun pieces(text: String): LongArray = tokenizer.encode(text, false, false).ids.let { if (it.size > SIDE) it.copyOf(SIDE) else it }

    override fun score(pairs: List<Pair<String, String>>): FloatArray {
        // Every text is cut into pieces once, however many pairs it is in
        val cut = HashMap<String, LongArray>()
        val sequences = pairs.map { (first, second) ->
            val a = cut.getOrPut(first) { pieces(first) }
            val b = cut.getOrPut(second) { pieces(second) }
            longArrayOf(bos) + a + longArrayOf(eos, eos) + b + longArrayOf(eos)
        }
        val out = FloatArray(pairs.size)
        val order = sequences.indices.sortedBy { sequences[it].size }
        val wantsTypes = TOKEN_TYPES in onnx.inputNames(MODEL)
        for (batch in order.chunked(if (onnx.onGpu(MODEL)) GPU_BATCH else BATCH)) {
            val len = batch.maxOf { sequences[it].size }
            val ids = LongArray(batch.size * len) { PAD }
            val mask = LongArray(batch.size * len)
            batch.forEachIndexed { r, s ->
                sequences[s].forEachIndexed { j, id ->
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
            val logits = onnx.run(MODEL, inputs)
            batch.forEachIndexed { r, s -> out[s] = logits[r][0] }
        }
        return out
    }

    companion object {
        /** Kept with every score: scores of another reranker are made again. */
        const val RERANKER = "bge-reranker-v2-m3"
        const val MODEL = "bge-reranker-v2-m3-fp16.onnx"
        const val TOKENIZER = "bge-reranker-v2-m3-tokenizer.json"
        private const val TOKEN_TYPES = "token_type_ids"
        private const val PAD = 1L

        /** The tokens of each text read: two of them and four special ones make at most 512. */
        private const val SIDE = 254
        private const val BATCH = 8
        private const val GPU_BATCH = 64
    }
}
