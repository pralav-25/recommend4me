package io.github.vlsergey.recommend4me.universe

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertTrue

/** Against Wikidata itself: run with WIKIDATA_LIVE=1, the build does not depend on the service. */
class WikidataCatalogueTest {

    private val catalogue = WikidataCatalogue(JsonMapper.builder().build())
    private val languages = listOf("ru", "en")

    @BeforeEach
    fun live() = assumeTrue(System.getenv("WIKIDATA_LIVE") != null, "WIKIDATA_LIVE is not set")

    @Test
    fun `a name finds its series among the variants`() {
        val found = catalogue.findUniverses("Гарри Поттер", languages)
        assertTrue(found.any { it.id == "Q8337" }, found.joinToString { "${it.id} ${it.name}" })
        // A fandom's whole name finds something of the universe too, the user picks
        assertTrue(catalogue.findUniverses("Роулинг Джоан «Гарри Поттер»", languages).isNotEmpty())
    }

    @Test
    fun `the characters of a series are found with the names the fans write`() {
        val characters = catalogue.characters("Q8337", languages)
        val neville = characters.firstOrNull { it.id == "Q190366" }
        assertTrue(neville != null && "Невилл Лонгботтом" in neville.names, neville?.names.toString())
        // A place the story is about is said to be in it too
        assertTrue(characters.any { it.id == "Q174097" }, "Hogwarts")
        assertTrue(characters.size > 100, "${characters.size} characters")
    }

    @Test
    fun `the statements a plugin asks for come with the characters`() {
        // P8419: the character's tag on Archive of Our Own
        val hermione = catalogue.characters("Q8337", languages, setOf("P8419")).first { it.id == "Q174009" }
        assertTrue("Hermione Granger" in hermione.properties["P8419"].orEmpty(), hermione.properties.toString())
    }

    @Test
    fun `the classes tell the characters from the places and the spells`() {
        val characters = catalogue.characters("Q8337", languages)
        val neville = characters.first { it.id == "Q190366" }
        val hogwarts = characters.first { it.id == "Q174097" }
        val classes = catalogue.classes((neville.classes + hogwarts.classes).toSet(), languages).associateBy { it.id }
        assertTrue(neville.classes.any { classes[it]?.character == true }, "Neville: ${neville.classes.map { classes[it]?.name }}")
        assertTrue(hogwarts.classes.none { classes[it]?.character == true }, "Hogwarts: ${hogwarts.classes.map { classes[it]?.name }}")
    }
}
