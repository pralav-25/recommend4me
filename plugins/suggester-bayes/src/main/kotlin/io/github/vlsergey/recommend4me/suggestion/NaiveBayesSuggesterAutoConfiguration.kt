package io.github.vlsergey.recommend4me.suggestion

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class NaiveBayesSuggesterAutoConfiguration {
    @Bean
    fun naiveBayesSuggester(): FacetSuggester = NaiveBayesSuggester()
}
