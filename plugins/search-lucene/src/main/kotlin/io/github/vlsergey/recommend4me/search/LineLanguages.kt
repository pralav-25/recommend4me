package io.github.vlsergey.recommend4me.search

import java.lang.Character.UnicodeScript
import java.util.Locale

/**
 * THE LANGUAGE A LINE SEARCHED IS READ AS: the interface's, the one it is typed in — unless it is
 * written in letters the interface's language is not written in (user, 2026-10-16): a line of a few
 * words tells no language by itself, its letters it does. Latin letters under a Russian interface
 * are read as English, Cyrillic under an English one as Russian ([OF_SCRIPT]).
 */
object LineLanguages {
    /** The language a line of a script not the interface's is read as. */
    private val OF_SCRIPT = mapOf(
        UnicodeScript.LATIN to "en",
        UnicodeScript.CYRILLIC to "ru",
        UnicodeScript.GREEK to "el",
        UnicodeScript.ARABIC to "ar",
        UnicodeScript.ARMENIAN to "hy",
        UnicodeScript.DEVANAGARI to "hi",
        UnicodeScript.BENGALI to "bn",
        UnicodeScript.TAMIL to "ta",
        UnicodeScript.TELUGU to "te",
        UnicodeScript.HEBREW to "yi",
    )

    /** Of a language written in two scripts, both: Serbian in Cyrillic and in Latin. */
    private val BOTH = mapOf("sr" to setOf(UnicodeScript.CYRILLIC, UnicodeScript.LATIN))

    /** The [line]'s language, the [interface]'s ([Stemmers.of]) unless the line is written in letters of another script. */
    fun of(line: String, `interface`: String): String {
        val own = Stemmers.of(`interface`)
        val script = scriptOf(line) ?: return own
        if (script in scriptsOf(`interface`)) return own
        return Stemmers.of(OF_SCRIPT[script] ?: return own)
    }

    /** The script most of the letters of [text] are of; null of a text of no letters. */
    private fun scriptOf(text: String): UnicodeScript? =
        text.codePoints().filter(Character::isLetter).mapToObj(UnicodeScript::of).toList().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** The scripts the [language] is written in: of its own name in itself. */
    private fun scriptsOf(language: String): Set<UnicodeScript> =
        BOTH[language] ?: setOfNotNull(scriptOf(Locale.of(language).getDisplayLanguage(Locale.of(language))))
}
