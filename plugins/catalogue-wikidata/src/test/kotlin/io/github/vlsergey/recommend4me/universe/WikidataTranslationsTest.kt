package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.i18n.Translations
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/** The catalogue carries its title in every language of the interface. */
class WikidataTranslationsTest {
    @Test
    fun `the title of the catalogue is in every language`() {
        val catalogue = WikidataCatalogue(JsonMapper.builder().build())
        assertEquals(emptyList(), Translations.titleProblems(catalogue.id, javaClass.classLoader))
    }
}
