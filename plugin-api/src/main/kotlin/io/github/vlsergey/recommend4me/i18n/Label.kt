package io.github.vlsergey.recommend4me.i18n

/**
 * A TEXT SHOWN TO THE USER, IN EVERY LANGUAGE OF THE INTERFACE: English, Russian, Serbian (Latin
 * script). Every one is required — a label without a language does not compile — so a language
 * added to the interface is added to every label at once.
 *
 * The application shows the label in the language of the request; what the model and the search
 * read keeps one language, [ru], whatever the user reads: their texts are encoded once.
 */
class Label(val en: String, val ru: String, val sr: String) {

    /** The text in [language] — "en", "ru", "sr" — English for any other. */
    fun of(language: String): String = when (language) {
        "ru" -> ru
        "sr" -> sr
        else -> en
    }

    override fun equals(other: Any?): Boolean = other is Label && en == other.en && ru == other.ru && sr == other.sr

    override fun hashCode(): Int = (en.hashCode() * 31 + ru.hashCode()) * 31 + sr.hashCode()

    override fun toString(): String = en

    companion object {
        /** The languages of the interface, by their codes; the first is the fallback. */
        val LANGUAGES = listOf("en", "ru", "sr")

        /** A label the same in every language: a name — "AO3", "F95zone" — or a symbol. */
        fun same(text: String) = Label(text, text, text)
    }
}
