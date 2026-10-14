package io.github.vlsergey.recommend4me.universe

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

@AutoConfiguration
class WikidataAutoConfiguration {
    /** Of its own class: a catalogue of universes and of tags both */
    @Bean
    fun wikidataCatalogue(json: JsonMapper): WikidataCatalogue = WikidataCatalogue(json)
}
