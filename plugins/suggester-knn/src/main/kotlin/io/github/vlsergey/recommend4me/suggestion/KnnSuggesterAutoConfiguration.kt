package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [OnnxAutoConfiguration::class])
class KnnSuggesterAutoConfiguration {
    /** The likeness of every work to every other on the card, through the process's one ONNX runtime, when the launch asks for the card. */
    @Bean
    fun knnSuggester(onnx: Onnx): FacetSuggester = KnnSuggester(onnx)
}
