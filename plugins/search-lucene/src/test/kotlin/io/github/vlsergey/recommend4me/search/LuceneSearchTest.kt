package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuceneSearchTest {

    private fun doc(id: String, title: String, author: String, text: String, language: String = "en") = SearchDocument(
        ItemKey("test", id),
        listOf(
            SearchField("title", "Название", 1.0f, listOf(title), naming = true),
            SearchField("author", "Автор", 1.0f, listOf(author), list = true, naming = true),
            SearchField("annotation", "Аннотация", 0.6f, listOf(text)),
        ),
        emptyList(),
        language,
    )

    private val documents = listOf(
        doc("1", "Stolen Memories", "Herandu Games", "A detective story in a rainy city."),
        doc("2", "Beach Day", "Somebody", "Her clothes were stolen at the beach, and the day went on from there."),
        doc("3", "Повелитель драконов", "Иван Петров", "Юноша находит яйцо дракона и становится всадником.", "ru"),
    )

    private val context = object : SearchContext {
        override fun queryVector(line: String): FloatArray? = null
        override fun phraseVectors(texts: Collection<String>): Map<String, FloatArray> = emptyMap()
        override fun documents(keys: Collection<ItemKey>) = documents.filter { it.key in keys }.associateBy { it.key }
    }

    @Test
    fun `words in any form, names typed in part, Russian endings`() {
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", documents.asSequence())
            // The words standing together in one text beat a title that has one of them
            val clothes = search.search("books", "stolen clothes", "en", context)
            assertEquals("2", clothes.keys.first().id)
            // A developer's name typed in part
            assertEquals(listOf("1"), search.search("books", "herand", "en", context).keys.map { it.id })
            // Another case of a Russian word
            assertEquals(listOf("3"), search.search("books", "драконы", "ru", context).keys.map { it.id })

            val match = search.matches("books", clothes, clothes.keys, context).getValue(ItemKey("test", "2"))
            // The field by its key: the application tells where in the work it is
            assertEquals("annotation", match.field)
            assertTrue(match.highlights.isNotEmpty())
        }
    }

    @Test
    fun `found by the meaning of a passage of a chapter, which goes with its item`() {
        val chapter = "Дождь шёл всю ночь. Наутро они встретились у маяка и долго молчали."
        val scene = chapter.indexOf("Наутро")
        val lighthouse = floatArrayOf(1f, 0f, 0f)
        val withChapter = SearchDocument(
            ItemKey("test", "4"),
            listOf(
                SearchField("title", "Название", 1.0f, listOf("Тихий берег"), naming = true),
                SearchField("annotation", "Аннотация", 0.6f, listOf("История о двух людях у моря.")),
                SearchField("part:1", "Глава 1", 0.5f, listOf(chapter)),
            ),
            listOf(
                SearchPassage("annotation", 0, 28, floatArrayOf(0f, 1f, 0f)),
                SearchPassage("part:1", 0, scene, floatArrayOf(0f, 0f, 1f)),
                SearchPassage("part:1", scene, chapter.length, lighthouse),
            ),
            "ru",
        )
        val other = SearchDocument(
            ItemKey("test", "5"), listOf(SearchField("title", "Название", 1.0f, listOf("Другое"), naming = true)),
            listOf(SearchPassage("title", 0, 6, floatArrayOf(0f, 1f, 0f))),
            "ru",
        )
        val byMeaning = object : SearchContext by context {
            override fun queryVector(line: String) = lighthouse
            override fun documents(keys: Collection<ItemKey>) = listOf(withChapter, other).filter { it.key in keys }.associateBy { it.key }
        }
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", sequenceOf(withChapter, other))
            val found = search.search("books", "свидание у маяка утром", "ru", byMeaning)
            assertEquals("4", found.keys.first().id)
            val nearest = found.nearest.getValue(ItemKey("test", "4"))
            assertEquals("part:1", nearest.field)
            assertEquals(chapter.substring(scene), nearest.text(withChapter))
            val shown = search.matches("books", found, listOf(ItemKey("test", "4")), byMeaning).getValue(ItemKey("test", "4"))
            assertEquals("part:1", shown.field)
            assertTrue(shown.byMeaning && shown.text.startsWith("Наутро"), shown.text)
            // Removed, the item's passages go with it
            search.update("books", emptyList(), listOf(ItemKey("test", "4")))
            assertTrue(ItemKey("test", "4") !in search.search("books", "свидание у маяка утром", "ru", byMeaning).keys)
        }
    }

    @Test
    fun `a work found by a passage of no long phrase is shown with the passage`() {
        val tags = "exhibitionism, nudity, public"
        val doc = SearchDocument(
            ItemKey("test", "8"),
            listOf(SearchField("title", "Название", 1.0f, listOf("Обнажённая"), naming = true), SearchField("tags", "Теги", 0.9f, listOf(tags))),
            listOf(SearchPassage("tags", 0, tags.length, floatArrayOf(1f, 0f, 0f))),
            "ru",
        )
        val byMeaning = object : SearchContext by context {
            override fun queryVector(line: String) = floatArrayOf(1f, 0f, 0f)
            override fun documents(keys: Collection<ItemKey>) = listOf(doc).filter { it.key in keys }.associateBy { it.key }
        }
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", sequenceOf(doc))
            val found = search.search("books", "голышом", "ru", byMeaning)
            val shown = search.matches("books", found, found.keys, byMeaning).getValue(ItemKey("test", "8"))
            assertEquals("tags", shown.field)
            assertEquals(tags, shown.text)
        }
    }

    @Test
    fun `a work of a thousand chapters is one field of chapters searched`() {
        val chapters = (1..1100).map { SearchField("part:$it", "Глава $it", 0.5f, listOf("Глава о том, как прошёл день номер $it."), group = "parts") }
        val long = SearchDocument(ItemKey("test", "7"), listOf(SearchField("title", "Название", 1.0f, listOf("Долгая сага"), naming = true)) + chapters, emptyList(), "ru")
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", sequenceOf(long))
            val found = search.search("books", "прошёл день", "ru", context)
            assertEquals(listOf("7"), found.keys.map { it.id })
        }
    }

    @Test
    fun `the index is kept between the runs, only what changed written again`() {
        val dir = Files.createTempDirectory("search")
        LuceneIndex(dir.resolve("types").resolve("books").resolve("search-index")).use { index ->
            assertEquals(3, index.sync(documents.asSequence()))
            // Nothing changed: nothing written
            assertEquals(0, index.sync(documents.asSequence()))
        }
        // Another run: the index opened again as it was left
        LuceneIndex(dir.resolve("types").resolve("books").resolve("search-index")).use { index ->
            val changed = listOf(documents[0], doc("2", "Beach Night", "Somebody", "The night at the beach."))
            assertEquals(1, index.sync(changed.asSequence()))
            index.search { searcher -> assertEquals(2, index.items(searcher)) }
        }
        LuceneSearch(dir).use { search ->
            assertEquals(listOf("2"), search.search("books", "night", "en", context).keys.map { it.id })
            assertTrue(search.search("books", "драконы", "ru", context).keys.isEmpty())
        }
    }

    @Test
    fun `an update is found without a rebuild`() {
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", documents.take(1).asSequence())
            search.update("books", listOf(doc("9", "Lighthouse Keeper", "Nobody", "A quiet game about a lighthouse.")), emptyList())
            assertEquals(listOf("9"), search.search("books", "lighthouse", "en", context).keys.map { it.id })
            search.update("books", emptyList(), listOf(ItemKey("test", "9")))
            assertTrue(search.search("books", "lighthouse", "en", context).keys.isEmpty())
        }
    }
}
