package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The extension finds on the site's pages what the application knows of them. */
class Ao3DecorTest {

    private val source = Ao3()
    private val decor = source.pageDecor
    private val now = Instant.parse("2026-10-13T10:00:00Z")

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    @Test
    fun `the addresses of a work's pages name the work`() {
        assertEquals("93948516", source.itemIdOf("https://archiveofourown.org/works/93948516"))
        assertEquals("93948516", source.itemIdOf("https://archiveofourown.org/works/93948516/chapters/250718281#workskin"))
        assertEquals("93948516", source.itemIdOf("https://archiveofourown.org/works/93948516?view_full_work=true"))
        assertNull(source.itemIdOf("https://archiveofourown.org/works/search?work_search%5Bquery%5D=x"))
        assertNull(source.itemIdOf("https://archiveofourown.org/tags/Angst/works"))
    }

    @Test
    fun `a work's page and a chapter's have the place of the panel and of the lines of tags`() {
        listOf("full.html", "work.html", "oneshot.html").forEach { name ->
            val doc = Jsoup.parse(page(name), "https://archiveofourown.org/works/1")
            assertEquals(1, doc.select(decor.panelAfter!!).size, name)
            decor.facets.forEach { assertEquals(1, doc.select(it.after!!).size, "$name: ${it.after}") }
        }
    }

    @Test
    fun `the comments of a page are the ones kept`() {
        val html = page("comments.html")
        val context = MemorySourceContext()
        source.capture(CapturedPage("https://archiveofourown.org/works/89821571/chapters/238641631?show_comments=true", html, now), context)
        val r = decor.reviews!!
        val ids = Jsoup.parse(html).select(r.selector).map { it.attr(r.idAttribute).removePrefix(r.idPrefix) }
        assertTrue(ids.isNotEmpty())
        assertEquals(context.reviews.getValue("89821571").keys, ids.toSet())
    }

    @Test
    fun `every card of a list links to its work`() {
        val html = page("list.html")
        val context = MemorySourceContext()
        val url = "https://archiveofourown.org/tags/Fullmetal%20Alchemist%20-%20All%20Media%20Types/works"
        val captured = source.capture(CapturedPage(url, html, now), context)
        val cards = decor.cards!!
        val linked = Jsoup.parse(html, url).select(cards.selector).mapNotNull { it.selectFirst(cards.link)?.absUrl("href") }.mapNotNull(source::itemIdOf)
        assertEquals(captured, linked)
    }
}
