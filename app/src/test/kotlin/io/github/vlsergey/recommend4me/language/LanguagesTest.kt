package io.github.vlsergey.recommend4me.language

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.suggestion.Chance
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The language of a work: by its code, of a book one. */
class LanguagesTest {

    @Test
    fun `a language is its code, whatever a site writes`() {
        assertEquals("en", StandardLanguages.codeOf("English"))
        assertEquals("zh", StandardLanguages.codeOf(" chinese "))
        assertNull(StandardLanguages.codeOf("Elvish"))
        assertEquals("pt", StandardLanguages.code("pt-BR"))
        assertEquals("ru", StandardLanguages.code("ru"))
        assertNull(StandardLanguages.code("english"))
        // Named as the model reads it: in Russian
        assertEquals("русский", StandardLanguages.NAMES["ru"])
    }

    @Test
    fun `of a facet of one value the model gives the likeliest alone, and the user's answer takes the place of the rest`() {
        val books = FacetDef(StandardLanguages.KEY, infer = true, single = true)
        val games = FacetDef(StandardLanguages.KEY, infer = true)
        fun chance(key: String, p: Double) = Chance("1", StandardLanguages.KEY, key, false, p)
        val chances = mapOf(StandardLanguages.KEY to mapOf("ru" to chance("ru", 0.9), "en" to chance("en", 0.7)))
        assertEquals(listOf("ru"), ModelValues.of(SourceSchema(listOf(books)), emptyMap(), chances)[StandardLanguages.KEY])
        assertEquals(listOf("ru", "en"), ModelValues.of(SourceSchema(listOf(games)), emptyMap(), chances)[StandardLanguages.KEY])

        val answered = listOf(FacetCorrection("1", StandardLanguages.KEY, "uk", true, null))
        val given = mapOf(StandardLanguages.KEY to listOf("ru"))
        assertEquals(listOf("uk"), Corrected.facets(given, answered, setOf(StandardLanguages.KEY))[StandardLanguages.KEY])
        assertEquals(listOf("ru", "uk"), Corrected.facets(given, answered)[StandardLanguages.KEY])
    }
}
