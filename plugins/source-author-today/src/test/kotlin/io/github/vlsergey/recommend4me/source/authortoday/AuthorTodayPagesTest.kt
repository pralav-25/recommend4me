package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorTodayPagesTest {

    private val source = AuthorToday()
    private val now = Instant.parse("2026-10-06T10:00:00Z")

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, html: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return source.capture(CapturedPage(url, html, now), context)
    }

    @Test
    fun `the cards of a genre give the works`() {
        val context = MemorySourceContext()
        val ids = capture(context, "https://author.today/work/genre/fantasy", page("genre-fantasy.html"))
        assertTrue(ids.size >= 20, "${ids.size} cards")
        val head = context.heads.getValue("661269")
        assertEquals("Ученик Белого Дьявола - 9", head.title)
        assertEquals(Instant.parse("2026-10-05T22:57:15.913Z"), head.updatedAt)
        val facets = context.facets.getValue("661269")
        assertEquals(listOf("siu_tower_of_god"), facets["author"])
        assertEquals("Джон Голд", context.facetNames.getValue("author")["siu_tower_of_god"])
        assertEquals(listOf("novel"), facets["form"])
        assertEquals(listOf("popadantsy-v-magicheskie-miry", "wuxia", "sf-action"), facets["genre"])
        // Not filed under "Фанфик": an original work
        assertEquals(listOf("original"), facets["kind"])
        // A fan fiction of the list is one
        assertTrue(context.facets.values.any { it["genre"].orEmpty().contains("fanfiction") && it["kind"] == listOf("fanfiction") })
        assertEquals(listOf("in-progress"), facets["status"])
        assertEquals(listOf("54335"), facets["series"])
        val numbers = context.numbers.getValue("661269")
        assertEquals(95898.0, numbers["chars"])
        assertEquals(14811.0, numbers["views"])
        assertEquals(1278.0, numbers["likes"])
        assertEquals(289.0, numbers["comments"])
        assertTrue(context.texts.getValue("661269").getValue("annotation").startsWith("Тайна южного берега Дуата"))
        assertEquals(listOf("https://cm.author.today/content/2026/10/02/8c93cd9c617e493aab150ac8eaa5c6ed.jpg"), context.pictures["661269"])
        // A guest's page says nothing of the user's library
        assertTrue(context.signalValues.isEmpty())
    }

    @Test
    fun `a work's page gives its tags, notes and table of contents`() {
        val context = MemorySourceContext()
        assertEquals(listOf("661269"), capture(context, "https://author.today/work/661269", page("work-661269.html")))
        val facets = context.facets.getValue("661269")
        assertTrue("выживание" in facets.getValue("tag"), facets.toString())
        assertEquals(1279.0, context.numbers.getValue("661269")["likes"])
        val texts = context.texts.getValue("661269")
        assertTrue(texts.getValue("annotation").startsWith("Тайна южного берега"))
        assertTrue(texts.getValue("notes").startsWith("- График выкладки"), texts["notes"])
        val parts = context.parts.getValue("661269")
        // The locked fourth chapter has no address and is not a part
        assertEquals(listOf("6337965", "6344849", "6362286"), parts.keys.toList())
        assertEquals("Глава 1. Прикрывая тылы", parts.getValue("6337965").title)
        assertNull(parts.getValue("6337965").content)
    }

    @Test
    fun `reviews come as excerpts and are completed by their own pages`() {
        val context = MemorySourceContext()
        capture(context, "https://author.today/work/216859", page("work-216859.html"))
        capture(context, "https://author.today/work/216859/reviews", page("work-216859-reviews.html"))
        val excerpt = context.reviews.getValue("216859").getValue("913603")
        assertEquals("Владислав Мкртчьян", excerpt.author)
        assertTrue(excerpt.content.endsWith("..."), excerpt.content)
        assertEquals(12, context.reviews.getValue("216859").size)

        capture(context, "https://author.today/review/913603", page("review-913603.html"))
        val whole = context.reviews.getValue("216859").getValue("913603").content
        assertTrue(whole.length > excerpt.content.length * 2, "${whole.length}")
        // The list read again does not cut the review back to its excerpt
        capture(context, "https://author.today/work/216859/reviews", page("work-216859-reviews.html"))
        assertEquals(whole, context.reviews.getValue("216859").getValue("913603").content)
    }

    @Test
    fun `the reader gives the text of a chapter once it is loaded`() {
        val context = MemorySourceContext()
        val empty = "<html><head><title>Книга Ученик Белого Дьявола - 9, Глава 1. Прикрывая тылы, Джон Голд читать онлайн</title></head>" +
            "<body><div id=\"reader\"><div id=\"text-container\" class=\"text-container\"></div></div></body></html>"
        assertEquals(emptyList(), capture(context, "https://author.today/reader/661269/6337965", empty))
        val loaded = empty.replace(
            "<div id=\"text-container\" class=\"text-container\"></div>",
            "<div id=\"text-container\" class=\"text-container\"><h1>Глава 1. Прикрывая тылы</h1><p><em>55 сутки, север Дуата</em></p><p>Гуам давно меня заметил.</p></div>",
        )
        assertEquals(listOf("661269"), capture(context, "https://author.today/reader/661269/6337965", loaded))
        // The reader before the work's page: the work is named after the page's title
        assertEquals("Ученик Белого Дьявола - 9", context.heads.getValue("661269").title)
        val part = context.parts.getValue("661269").getValue("6337965")
        assertEquals("Глава 1. Прикрывая тылы", part.title)
        assertEquals("55 сутки, север Дуата\n\nГуам давно меня заметил.", part.content)
    }

    @Test
    fun `a logged-in page gives the user's own marks`() {
        val context = MemorySourceContext()
        val html = page("work-661269.html")
            .replace("isAuthenticated: false", "isAuthenticated: true")
            .replace("state: 'None'", "state: 'Reading'")
            .replace("voteId: null", "voteId: 123")
        capture(context, "https://author.today/work/661269", html)
        assertEquals("reading", context.signalValues["661269" to "shelf"])
        assertEquals(null, context.signalValues["661269" to "read"])
        assertEquals("yes", context.signalValues["661269" to "liked"])
        assertNotNull(context.heads["661269"])
        // The shelf of the finished is the standard "read" too
        capture(context, "https://author.today/work/661269", html.replace("state: 'Reading'", "state: 'Finished'"))
        assertEquals("finished", context.signalValues["661269" to "shelf"])
        assertEquals("yes", context.signalValues["661269" to "read"])
    }

    @Test
    fun `a work's page without the block of tags or the tab of texts leaves them as they were`() {
        val context = MemorySourceContext()
        val url = "https://author.today/work/661269"
        capture(context, url, page("work-661269.html"))
        val tags = context.facets.getValue("661269").getValue("tag")
        val texts = context.texts.getValue("661269").toMap()
        assertTrue(tags.isNotEmpty() && texts.isNotEmpty())

        val changed = Jsoup.parse(page("work-661269.html")).also { it.select(".book-meta-panel .tags, #tab-annotation").remove() }.outerHtml()
        assertEquals(listOf("661269"), capture(context, url, changed))
        assertEquals(tags, context.facets.getValue("661269").getValue("tag"))
        assertEquals(texts, context.texts.getValue("661269").toMap())
    }
}
