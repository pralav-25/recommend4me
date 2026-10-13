package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.i18n.Translations
import kotlin.test.Test
import kotlin.test.assertEquals

/** The source carries its words in every language of the interface, every key it needs. */
class FicbookTranslationsTest {
    @Test
    fun `the words of the source are complete`() {
        assertEquals(emptyList(), Translations.problems(Ficbook()))
    }
}
