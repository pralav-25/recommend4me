package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order

/**
 * BAAI/bge-m3 as the text encoder: `bge-m3-fp16.onnx` and `bge-m3-tokenizer.json` in the models
 * folder. Before any other text encoder: of the ones ready, the first is the one used.
 */
@AutoConfiguration(after = [OnnxAutoConfiguration::class])
class BgeM3AutoConfiguration {
    @Bean
    @Order(0)
    fun bgeM3Encoder(onnx: Onnx): TextEncoder = BgeM3Encoder(onnx)
}
