package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.i18n.Translations
import kotlin.test.Test
import kotlin.test.assertEquals

/** The scorer carries its title in every language of the interface. */
class KernelTranslationsTest {
    @Test
    fun `the title of the scorer is in every language`() {
        assertEquals(emptyList(), Translations.titleProblems(KernelScorer().id, javaClass.classLoader))
    }
}
