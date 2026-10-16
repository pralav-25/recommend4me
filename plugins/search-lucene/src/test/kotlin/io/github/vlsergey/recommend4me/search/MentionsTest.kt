package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MentionsTest {

    private val names = mapOf(
        "harry" to listOf("Гарри Поттер", "Harry Potter"),
        "ron" to listOf("Рон Уизли"),
        "ginny" to listOf("Джинни Уизли"),
        "captain" to listOf("Captain"),
    )

    private fun doc(id: String, language: String, vararg texts: String, tags: List<String> = emptyList(), given: List<String> = emptyList()) = SearchDocument(
        ItemKey("test", id),
        listOf(SearchField("title", "title", 1f, listOf("Title $id"), naming = true)) +
            texts.mapIndexed { i, t -> SearchField("part:$i", "part", 0.5f, listOf(t), group = "parts", counted = true) } +
            listOfNotNull(
                tags.takeIf { it.isNotEmpty() }?.let { SearchField("facet.tag", "tag", 0.9f, it, list = true, counted = true) },
                given.takeIf { it.isNotEmpty() }?.let { SearchField("facet.tag", "tag", 0.9f, it, list = true, group = "facet.tag+given") },
            ),
        emptyList(),
        language,
    )

    private val documents = listOf(
        // Every form of a name; a word of a family names nobody; a name of one word only with a capital
        doc(
            "1", "ru",
            "Гарри Поттера не было дома. Поттер вернулся к вечеру, и Гарри сел у огня. Рон Уизли пришёл позже. Уизли шумели.",
            "Джинни молчала. гарри — не имя здесь.",
        ),
        // English stemmed as English, the capital told apart; the site's tags count, the values given do not
        doc("2", "en", "The Captain waits. The captain of the guard does not.", tags = listOf("Harry Potter"), given = listOf("Ron Weasley", "Captain")),
        doc("3", "en", "Nobody is named here at all."),
    )

    @Test
    fun `the works' own words name the values by the rules of the names, counted of the index as of the texts`() {
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", documents.asSequence())
            val counted = search.mentions("books", null, names)
            assertEquals(
                mapOf(
                    ItemKey("test", "1") to mapOf("harry" to 3, "ron" to 1, "ginny" to 1),
                    ItemKey("test", "2") to mapOf("captain" to 1, "harry" to 1),
                ),
                counted,
            )
            assertEquals(mapOf(ItemKey("test", "2") to mapOf("captain" to 1, "harry" to 1)), search.mentions("books", listOf(ItemKey("test", "2"), ItemKey("test", "3")), names))
            // What is counted is what is shown: the places of a value of a work read of its texts again
            documents.forEach { doc ->
                names.keys.forEach { value ->
                    val places = search.places(doc, names, listOf(value)).sumOf { it.ranges.size }
                    assertEquals(counted[doc.key]?.get(value) ?: 0, places, "${doc.key} $value")
                }
            }
            val harry = search.places(documents[0], names, listOf("harry"))
            assertEquals(listOf("Гарри Поттера", "Поттер", "Гарри"), harry.flatMap { p -> p.ranges.map { p.text.substring(it) } })
        }
    }
}
