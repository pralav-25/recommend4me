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

    private val potter = mapOf(
        "harry" to listOf("Гарри Поттер", "Harry Potter"),
        "hermione" to listOf("Гермиона Грейнджер", "Hermione Granger"),
        "james" to listOf("Джеймс Поттер"),
        "ron" to listOf("Рон Уизли"),
        "ginny" to listOf("Джинни Уизли", "Джиневра Уизли"),
        "c3po" to listOf("Си-Три-Пи-О"),
        "saw" to listOf("Со Геррера"),
        "rick" to listOf("Рик Джонс", "Нова"),
    )

    @Test
    fun `the rules of the names, a family name and a function word naming nobody, a word alone only with a capital`() {
        val docs = listOf(
            doc("d", "ru", "Гарри посмотрел на Гермиону. Гермионы не было. Гарри Поттер ушёл к Джинни."),
            doc("f", "ru", "Поттер и Уизли вошли. Джеймс Поттер остался."),
            doc("w", "ru", "Пришёл со мной о чём-то. Со Геррера и Пи. пи равно трём."),
            doc("n", "ru", "Однако новый мир — новые правила. Нова улетел."),
        )
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", docs.asSequence())
            val counted = search.mentions("books", null, potter)
            assertEquals(mapOf("harry" to 2, "hermione" to 2, "ginny" to 1), counted[ItemKey("test", "d")])
            assertEquals(mapOf("james" to 1), counted[ItemKey("test", "f")])
            assertEquals(mapOf("saw" to 1, "c3po" to 1), counted[ItemKey("test", "w")])
            assertEquals(mapOf("rick" to 1), counted[ItemKey("test", "n")])
        }
    }

    @Test
    fun `two named in one paragraph are together, counted of the index as quoted of the texts`() {
        val text = "Гарри поцеловал Гермиону.\n\nРон ел.\nГермиона и Рон спорили. И снова Гермиона и Рон."
        val docs = listOf(doc("p", "ru", text), doc("q", "ru", "Гермиона.", "Рон."))
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", docs.asSequence())
            val together = search.together("books", null, potter)
            assertEquals(mapOf(("harry" to "hermione") to 1, ("hermione" to "ron") to 1), together[ItemKey("test", "p")])
            // Two texts are never one paragraph
            assertEquals(null, together[ItemKey("test", "q")])
            val quoted = search.placesTogether(docs[0], potter, "hermione", "ron").single()
            assertEquals(listOf("Гермиона", "Рон", "Гермиона", "Рон"), quoted.ranges.map { quoted.text.substring(it) })
        }
    }
}
