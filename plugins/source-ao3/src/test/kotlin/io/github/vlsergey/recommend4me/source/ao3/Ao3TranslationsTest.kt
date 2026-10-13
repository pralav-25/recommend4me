package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.i18n.Translations
import kotlin.test.Test
import kotlin.test.assertEquals

/** The source carries its words in every language of the interface, every key it needs. */
class Ao3TranslationsTest {
    @Test
    fun `the words of the source are complete`() {
        assertEquals(emptyList(), Translations.problems(Ao3()))
    }
}
