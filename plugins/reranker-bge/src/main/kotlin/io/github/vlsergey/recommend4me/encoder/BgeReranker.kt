package io.github.vlsergey.recommend4me.encoder

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import java.nio.LongBuffer
import java.nio.file.Files
import java.util.stream.IntStream

/**
 * A MULTILINGUAL CROSS-ENCODER: BAAI/bge-reranker-v2-m3 (XLM-RoBERTa large, Apache-2.0) as an ONNX
 * graph in half precision. The two texts go in as one sequence, `<s> first </s></s> second </s>`,
 * and the graph answers one number, the logit of the second answering the first.
 *
 * Each text is cut to [SIDE] tokens: a description of a work says what it is in its first lines,
 * and attention costs the square of the length. The texts are cut once ([prepare]), on every
 * core, however many pairs each is in: cut on the card's turn, they kept it waiting. Pairs are
 * SORTED BY LENGTH before batching, as the text encoder's windows are: a batch is padded to its
 * longest pair. On the card the batches are larger ([GPU_BATCH]).
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

    /** The pieces of every text, cut to [SIDE]. */
    private class Pieces(val of: Array<LongArray>) : PreparedTexts {
        override val size get() = of.size
    }

    override fun prepare(texts: List<String>): PreparedTexts {
        val out = arrayOfNulls<LongArray>(texts.size)
        // An equal share of the texts for every core, each share cut in one call
        val cores = Runtime.getRuntime().availableProcessors()
        val share = (texts.size + cores - 1) / cores
        IntStream.range(0, cores).parallel().forEach { k ->
            val from = k * share
            val to = minOf(texts.size, from + share)
            if (from >= to) return@forEach
            tokenizer.batchEncode(texts.subList(from, to), false, false).forEachIndexed { i, e ->
                out[from + i] = e.ids.let { if (it.size > SIDE) it.copyOf(SIDE) else it }
            }
        }
        return Pieces(Array(texts.size) { out[it]!! })
    }

    override fun score(texts: PreparedTexts, first: IntArray, second: IntArray): FloatArray {
        val pieces = (texts as Pieces).of
        fun length(k: Int) = pieces[first[k]].size + pieces[second[k]].size + 4
        val out = FloatArray(first.size)
        val order = first.indices.sortedBy(::length)
        val wantsTypes = TOKEN_TYPES in onnx.inputNames(MODEL)
        for (batch in order.chunked(if (onnx.onGpu(MODEL)) GPU_BATCH else BATCH)) {
            val len = batch.maxOf(::length)
            val ids = LongArray(batch.size * len) { PAD }
            val mask = LongArray(batch.size * len)
            batch.forEachIndexed { r, k ->
                // <s> first </s></s> second </s>
                var at = r * len
                fun put(id: Long) {
                    ids[at] = id
                    mask[at] = 1
                    at++
                }
                put(bos)
                pieces[first[k]].forEach(::put)
                put(eos)
                put(eos)
                pieces[second[k]].forEach(::put)
                put(eos)
            }
            val shape = longArrayOf(batch.size.toLong(), len.toLong())
            val inputs = mutableMapOf(
                "input_ids" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(ids), shape),
                "attention_mask" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(mask), shape),
            )
            if (wantsTypes) inputs[TOKEN_TYPES] = OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(LongArray(ids.size)), shape)
            val logits = onnx.run(MODEL, inputs)
            batch.forEachIndexed { r, k -> out[k] = logits[r][0] }
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
