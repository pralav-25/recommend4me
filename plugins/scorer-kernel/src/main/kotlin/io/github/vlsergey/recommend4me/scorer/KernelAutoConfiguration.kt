package io.github.vlsergey.recommend4me.scorer

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class KernelAutoConfiguration {
    @Bean
    fun kernelScorer(): Scorer = KernelScorer()
}
