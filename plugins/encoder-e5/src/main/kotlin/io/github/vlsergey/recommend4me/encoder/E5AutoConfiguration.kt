package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order

/**
 * multilingual-e5-small as the text encoder: `e5-small.onnx` and `e5-small-tokenizer.json` in the
 * models folder. After bge-m3: the one used when bge-m3's files are not there.
 */
@AutoConfiguration(after = [OnnxAutoConfiguration::class])
class E5AutoConfiguration {
    @Bean
    @Order(1)
    fun e5Encoder(onnx: Onnx): TextEncoder = E5Encoder(onnx)
}
