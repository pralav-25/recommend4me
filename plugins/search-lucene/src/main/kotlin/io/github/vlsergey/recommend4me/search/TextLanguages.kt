package io.github.vlsergey.recommend4me.search

import opennlp.tools.langdetect.LanguageDetectorME
import opennlp.tools.langdetect.LanguageDetectorModel
import java.util.Locale

/**
 * THE LANGUAGE A TEXT IS STEMMED AS ([Stemmers]): a text long enough to tell its own — of
 * [LONG_WORDS] words and more, a description, a chapter, a review — by itself, as Apache OpenNLP's
 * detector of 103 languages tells it; a shorter one, and one of a language no stemmer is known of,
 * as the work's description is written ([io.github.vlsergey.recommend4me.contenttype.StandardLanguages.DESCRIPTION])
 * — the user's word (2026-10-16).
 */
object TextLanguages {
    /** The fewest words a text tells its own language by: shorter, a few words of names and tags tell nothing sure. */
    const val LONG_WORDS = 50

    /** The detector, its model read once for the process: the model is the plugin's, not data. */
    private val detector: LanguageDetectorME by lazy {
        val model = TextLanguages::class.java.classLoader.getResourceAsStream(MODEL)?.use { LanguageDetectorModel(it) }
            ?: error("No model of the languages: $MODEL")
        LanguageDetectorME(model)
    }

    /** The detector's codes (ISO 639-3) to the stemmers' (ISO 639-1). */
    private val BY_THREE: Map<String, String> = Locale.getISOLanguages().associateBy { Locale.of(it).isO3Language }

    /** The language the [text] is stemmed as: its own when long enough and a stemmer is known of it, else the [work]'s ([Stemmers.of]). */
    fun of(text: String, work: String?): String {
        if (words(text) < LONG_WORDS) return Stemmers.of(work)
        val own = BY_THREE[detector.predictLanguage(text).lang]
        return if (own != null && own in Stemmers.languages) Stemmers.of(own) else Stemmers.of(work)
    }

    /** How many words the text has, up to [LONG_WORDS]: no more is wanted. */
    private fun words(text: String): Int {
        var n = 0
        var inWord = false
        for (c in text) {
            val letter = Character.isLetterOrDigit(c)
            if (letter && !inWord && ++n >= LONG_WORDS) return n
            inWord = letter
        }
        return n
    }

    private const val MODEL = "langdetect-183.bin"
}
