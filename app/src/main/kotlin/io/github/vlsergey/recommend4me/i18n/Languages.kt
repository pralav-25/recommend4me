package io.github.vlsergey.recommend4me.i18n

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

/**
 * THE LANGUAGE OF A REQUEST: the first of its `Accept-Language` the interface speaks — the web
 * interface sends the one the user chose, the browser extension the browser's — else English.
 * Every label the answer carries ([Label]) is in it.
 */
@Configuration
class Languages {

    @Bean
    fun localeResolver(): LocaleResolver = AcceptHeaderLocaleResolver().apply {
        supportedLocales = Label.LANGUAGES.map { Locale.of(it) }
        setDefaultLocale(Locale.of(Label.LANGUAGES.first()))
    }

    companion object {
        /** The language of the request being answered; English outside of one, whatever the machine's language. */
        fun current(): String =
            LocaleContextHolder.getLocaleContext()?.locale?.language?.takeIf { it in Label.LANGUAGES } ?: Label.LANGUAGES.first()
    }
}

/** The label in the language of the request being answered. */
fun Label.text(): String = of(Languages.current())

/** A request refused, told in its language: [message], its `%s` filled with [args] in their order. */
fun failure(status: HttpStatus, message: Label, vararg args: Any?) = ResponseStatusException(status, message.text().format(*args))
