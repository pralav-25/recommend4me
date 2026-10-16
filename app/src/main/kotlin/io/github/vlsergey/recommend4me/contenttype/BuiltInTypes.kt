package io.github.vlsergey.recommend4me.contenttype

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The content types the application knows by itself; a plugin may declare more. */
@Configuration(proxyBeanMethods = false)
class BuiltInTypes {

    @Bean
    fun games() = GAMES

    @Bean
    fun books() = Books.TYPE

    companion object {
        /**
         * Games: their title, grades and verb are the interface's words of "games"; their sites' free
         * tags normalized into the same dictionary as the books' ([StandardTags]); a game is played in
         * every language it is translated to ([StandardLanguages]), its description written in one
         * ([StandardLanguages.DESCRIPTION]).
         */
        val GAMES = ContentType(
            id = "games",
            facets = listOf(StandardTags.TAG, StandardLanguages.LANGUAGE),
            layer = listOf(StandardTags.TAGS, StandardLanguages.languages(single = false), StandardLanguages.DESCRIPTION),
            numbers = listOf(StandardNumbers.LIKES),
        )
    }
}
