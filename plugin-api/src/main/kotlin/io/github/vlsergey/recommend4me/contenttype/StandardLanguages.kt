package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.source.FacetRole
import java.util.Locale

/**
 * THE LANGUAGE OF A WORK, a language by its ISO 639 code ("ru", "en"): the site's word on it — the
 * language Archive of Our Own states of every work, the line "Language" of F95zone — and the
 * layer's: the language the work is in (of a game, every language it is played in), worked out for
 * every work from what is known of it and the user's answers, and the list filtered by. A site that
 * states none says it by itself: the model learns a site's works are in Russian from the user's
 * answers on them.
 */
object StandardLanguages {
    /** The language as the site states it, by its code: shown right above the work's ([languages]). */
    val LANGUAGE = StandardFacet("language", role = FacetRole.ORIGINAL)

    const val KEY = "languages"

    /**
     * The language a work is in: of a book one ([single]), of a game every language it is played in
     * — and then the user may say they are all named.
     */
    fun languages(single: Boolean) = StandardFacet(KEY, filter = true, infer = true, single = single, completable = !single, original = LANGUAGE)

    const val DESCRIPTION_KEY = "description-language"

    /**
     * THE LANGUAGE A WORK'S DESCRIPTION IS WRITTEN IN, one: what its texts are stemmed by in the
     * index, where a text too short to tell its own language by itself takes it — of a game,
     * whose [languages] are those it is played in, not the one its page is written in (user,
     * 2026-10-16). Of a work without it, the [languages] of a book are its description's.
     */
    val DESCRIPTION = StandardFacet(DESCRIPTION_KEY, single = true)

    /** Whether the facet [key] of the layer is of languages by their codes: named and shown as [NAMES]. */
    fun ofLanguages(key: String) = key == KEY || key == DESCRIPTION_KEY

    private val RUSSIAN: Locale = Locale.of("ru")

    /** Every language by its code, named as the model reads it — in Russian, one language whatever the user reads. */
    val NAMES: Map<String, String> = Locale.getISOLanguages().associateWith { Locale.of(it).getDisplayLanguage(RUSSIAN) }
        .filter { (code, name) -> name.isNotBlank() && name != code }

    private val BY_ENGLISH_NAME: Map<String, String> = NAMES.keys.associateBy { Locale.of(it).getDisplayLanguage(Locale.ENGLISH).lowercase() }

    /** The code of a language a site tags ("en", "pt-BR" — its region left out); null of a tag of no known language. */
    fun code(tag: String): String? = Locale.forLanguageTag(tag.trim()).language.takeIf { it in NAMES }

    /** The code of a language a site names in English ("English", "Chinese"), any case; null of a name of none. */
    fun codeOf(name: String): String? = BY_ENGLISH_NAME[name.trim().lowercase()]
}
