package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.i18n.Label
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The content types the application knows by itself; a plugin may declare more. */
@Configuration(proxyBeanMethods = false)
class BuiltInTypes {

    @Bean
    fun games() = ContentType(
        id = "games",
        title = Label("Games", "Игры", "Igre"),
        grades = listOf(
            Label("Dislike", "Не нравится", "Ne sviđa mi se"),
            Label("Worth a play", "Можно поиграть", "Može da se igra"),
            Label("Liked it overall", "В целом понравилась", "Uglavnom mi se svidela"),
            Label("Very good", "Очень хорошая", "Veoma dobra"),
            Label("Want more", "Хочу ещё", "Hoću još"),
        ),
        verb = Label("play", "играть", "igrati"),
        numbers = listOf(StandardNumbers.LIKES),
    )

    @Bean
    fun books() = Books.TYPE
}
