package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.universe.CatalogueLookup
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import kotlin.test.Test
import kotlin.test.assertEquals

class Ao3DraftTest {

    /** A dictionary stating the tags of Archive of Our Own of two characters, one universe, and one tag of two characters. */
    private val lookup = object : CatalogueLookup {
        private val characters = mapOf(
            "hermione granger" to setOf("wikidata:Q174009"),
            "harry potter" to setOf("wikidata:Q3244512"),
            "twins" to setOf("wikidata:Q1", "wikidata:Q2"),
        )

        override fun universes(catalogue: String, property: String, value: String) =
            if (catalogue == "wikidata" && property == "P8419" && value.lowercase() == "harry potter - j. k. rowling") setOf("wikidata:Q5410773") else emptySet()

        override fun characters(catalogue: String, property: String, value: String) =
            if (catalogue == "wikidata" && property == "P8419") characters[value.lowercase()].orEmpty() else emptySet()
    }

    private val ao3 = Ao3()

    @Test
    fun `the tags of a work draft its universe, its main characters and the pairing of a romantic relationship`() {
        val drafts = ao3.draft(
            mapOf(
                Ao3.FANDOM to listOf(FacetValue("Harry Potter - J. K. Rowling", "Harry Potter - J. K. Rowling"), FacetValue("Unknown Fandom", "Unknown Fandom")),
                Ao3.PAIRING to listOf(
                    FacetValue("harry potter/hermione granger", "Hermione Granger/Harry Potter"),
                    FacetValue("harry potter & ron weasley", "Harry Potter & Ron Weasley"),
                    FacetValue("twins", "Twins"),
                ),
            ),
            lookup,
        )
        assertEquals(
            setOf(
                LayerDraft(UniverseFacets.UNIVERSE, "wikidata:Q5410773", "Harry Potter - J. K. Rowling"),
                LayerDraft(UniverseFacets.CHARACTERS, "wikidata:Q174009", "Hermione Granger/Harry Potter"),
                LayerDraft(UniverseFacets.CHARACTERS, "wikidata:Q3244512", "Hermione Granger/Harry Potter"),
                LayerDraft(UniverseFacets.PAIRINGS, UniverseFacets.pairing("wikidata:Q174009", "wikidata:Q3244512"), "Hermione Granger/Harry Potter"),
            ),
            // A platonic relationship drafts its characters alone, not a pairing — and no new character
            // here; a tag two characters state is no draft at all
            drafts.toSet(),
        )
    }
}
