package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

/** bge-reranker-v2-m3 as the reranker: `bge-reranker-v2-m3-fp16.onnx` and `bge-reranker-v2-m3-tokenizer.json` in the models folder. */
@AutoConfiguration(after = [OnnxAutoConfiguration::class])
class BgeRerankerAutoConfiguration {
    @Bean
    fun bgeReranker(onnx: Onnx): TextReranker = BgeReranker(onnx)
}
