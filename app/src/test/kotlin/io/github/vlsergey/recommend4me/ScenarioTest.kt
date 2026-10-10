package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.ItemSort
import io.github.vlsergey.recommend4me.api.model.ItemView
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.capture.CaptureController
import io.github.vlsergey.recommend4me.contenttype.TypesController
import io.github.vlsergey.recommend4me.correction.CorrectionsController
import io.github.vlsergey.recommend4me.item.ItemsController
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals

/**
 * The whole application with a source of its own: a page captured from the browser becomes an
 * item, a grade is kept, a correction shows, the list filters and sorts.
 */
@SpringBootTest(properties = ["recommend4me.data-dir=build/test-data/scenario"])
class ScenarioTest {

    companion object {
        /** Every run from an empty data folder: what an earlier run left is no part of the scenario. */
        @JvmStatic
        @BeforeAll
        fun clean() {
            File("build/test-data/scenario").deleteRecursively()
        }
    }

    @TestConfiguration
    class Plugin {
        @Bean
        fun testSource(): Source = TestSource()
    }

    /** A site whose pages are "<id>|<title>|<tags>|<annotation>". */
    class TestSource : Source {
        override val id = "test"
        override val title = "Test"
        override val contentType = "books"
        override val homepage = "https://test.example"
        override val schema = SourceSchema(
            facets = listOf(FacetDef("tag", "Тег", filter = true, searchWeight = 0.9f, onCard = true)),
            numbers = listOf(NumberDef("likes", "Лайки", sortable = true, filterable = true)),
            texts = listOf(TextDef("annotation", "Аннотация", block = "text:annotation")),
        )
        override val modes = setOf(SourceMode.BROWSER)
        override val capturePatterns = listOf(Regex("https://test\\.example/work/.*"))
        override fun itemUrl(itemId: String) = "https://test.example/work/$itemId"

        override fun capture(page: CapturedPage, context: SourceContext): List<String> {
            val (id, title, tags, annotation) = page.html.split('|')
            context.items.upsert(ItemHead(id, itemUrl(id), title, updatedAt = Instant.parse("2026-01-0${id.last()}T00:00:00Z")), page.capturedAt)
            context.items.setFacet(id, "tag", tags.split(',').map { FacetValue(it, it.uppercase()) })
            context.items.setNumbers(id, mapOf("likes" to id.last().digitToInt().toDouble()))
            context.items.setTexts(id, mapOf("annotation" to annotation))
            return listOf(id)
        }
    }

    @Autowired
    lateinit var captures: CaptureController

    @Autowired
    lateinit var items: ItemsController

    @Autowired
    lateinit var corrections: CorrectionsController

    @Autowired
    lateinit var types: TypesController

    private fun capture(body: String) = captures.capturePage(CaptureRequest("https://test.example/work/${body.substringBefore('|')}", body)).body!!

    private fun list(
        view: ItemView = ItemView.ALL, sort: ItemSort = ItemSort.SCORE, sortNumber: String? = null, hidden: List<String>? = null, range: List<String>? = null,
    ) = items.listItems("books", view, sort, sortNumber, null, hidden, null, null, range, 0, 60).body!!

    @Test
    fun `a captured page becomes an item that can be graded, corrected, filtered and sorted`() {
        assertEquals("test", capture("w1|Dragons|fantasy,dragons|A boy finds a dragon egg").source)
        capture("w2|Office|romance|A romance at the office")
        capture("w3|Knights|fantasy|Knights and castles")
        assertEquals(emptyList(), captures.capturePage(CaptureRequest("https://elsewhere.example/", "x")).body!!.items)

        assertEquals("books", types.listTypes().body!!.single().id)
        val updated = list(sort = ItemSort.UPDATED)
        assertEquals(3, updated.total)
        assertEquals("w3", updated.items.first().item)

        assertEquals(5, items.rateItem("test", "w1", RatingRequest(5)).body!!.grade)
        assertEquals(1, list(view = ItemView.RATED).total)
        assertEquals(2, list(view = ItemView.UNRATED).total)

        // The site's tags are its word, not corrected; the list is filtered by them
        assertThrows<ResponseStatusException> { corrections.correctFacet("test", "w2", FacetCorrection(facet = "tag", added = true, key = "fantasy")) }
        assertEquals(2, list(hidden = listOf("test.tag=romance")).total)
        val notFantasy = list(hidden = listOf("test.tag=fantasy", "test.tag=dragons"))
        assertEquals(listOf("w2"), notFantasy.items.map { it.item })

        assertEquals("w3", list(sort = ItemSort.NUMBER, sortNumber = "test.likes").items.first().item)
        // Limited to a range of a number, both ends included, an end left out open
        assertEquals(listOf("test.likes"), types.listTypes().body!!.single().filterNumbers.map { it.id })
        assertEquals(setOf("w2", "w3"), list(range = listOf("test.likes=2..3")).items.map { it.item }.toSet())
        assertEquals(setOf("w1", "w2"), list(range = listOf("test.likes=..2")).items.map { it.item }.toSet())
        assertEquals(listOf("w3"), list(range = listOf("test.likes=3..")).items.map { it.item })
        // A number no source has limits nothing; a range not understood is refused
        assertEquals(3, list(range = listOf("other.parts=1..1")).total)
        assertThrows<ResponseStatusException> { list(range = listOf("test.likes=a..2")) }
        assertEquals("A boy finds a dragon egg", items.getItem("test", "w1").body!!.texts.single().content)
        // The source's filter facet, and the kind and the universe the application gives every book
        assertEquals(listOf("test.tag", "kind", "universe"), items.listFacets("books").body!!.map { it.id })

        // Removed from the library — gone from the site — with its grade, and not brought back by its page read again
        corrections.correctFacet("test", "w1", FacetCorrection(facet = "tags", added = true, key = "magic"))
        assertEquals(204, items.removeItem("test", "w1").statusCode.value())
        assertEquals(404, items.getItem("test", "w1").statusCode.value())
        assertEquals(0, list(view = ItemView.RATED).total)
        assertEquals(setOf("w2", "w3"), list().items.map { it.item }.toSet())
        capture("w1|Dragons|fantasy,dragons|A boy finds a dragon egg")
        assertEquals(404, items.getItem("test", "w1").statusCode.value())
        assertEquals(2, list().total)
        assertEquals(404, items.removeItem("test", "w1").statusCode.value())
    }
}
