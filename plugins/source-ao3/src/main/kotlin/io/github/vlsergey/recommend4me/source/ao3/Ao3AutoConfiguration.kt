package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.source.Source
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class Ao3AutoConfiguration {
    @Bean
    fun ao3(): Source = Ao3()
}
