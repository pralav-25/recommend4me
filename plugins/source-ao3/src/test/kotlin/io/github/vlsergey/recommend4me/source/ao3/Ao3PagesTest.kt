package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ao3PagesTest {

    private val source = Ao3()
    private val now = Instant.parse("2026-10-13T10:00:00Z")

    /** The work of four chapters of seven the pages of a work show. */
    private val saints = "93948516"

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, name: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return source.capture(CapturedPage(url, page(name), now), context)
    }

    @Test
    fun `the cards of a tag's list give the works with their header`() {
        val context = MemorySourceContext()
        val url = "https://archiveofourown.org/tags/Fullmetal%20Alchemist%20-%20All%20Media%20Types/works"
        assertEquals(listOf("94578656", "93589006", "74280956"), capture(context, url, "list.html"))
        val ishvalan = "74280956"
        assertEquals("https://archiveofourown.org/works/$ishvalan", context.heads.getValue(ishvalan).url)
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), context.heads.getValue(ishvalan).updatedAt)
        val facets = context.facets.getValue(ishvalan)
        assertEquals(listOf("Wolfgang555"), facets["author"])
        assertEquals(listOf("explicit"), facets["rating"])
        assertEquals(listOf(Books.Status.FINISHED.key), facets["status"])
        assertEquals(listOf("en"), facets["language"])
        assertEquals(listOf(UniverseFacets.FANFICTION), facets[UniverseFacets.KIND])
        assertTrue(facets["fandom"].orEmpty().isNotEmpty())
        assertEquals(37.0, context.numbers.getValue(ishvalan)["parts"])
        assertEquals(listOf(Books.Status.IN_PROGRESS.key), context.facets.getValue("94578656")["status"])
        assertTrue(context.texts.getValue("94578656")["annotation"].orEmpty().isNotBlank())
        // A card's link is what the extension marks the card up by
        assertEquals(ishvalan, source.itemIdOf("https://archiveofourown.org/works/$ishvalan"))
    }

    @Test
    fun `a whole work's page gives its header and every chapter's text`() {
        val context = MemorySourceContext()
        assertEquals(listOf(saints), capture(context, "https://archiveofourown.org/works/$saints?view_full_work=true", "full.html"))
        val head = context.heads.getValue(saints)
        assertEquals("We're Not Saints", head.title)
        val facets = context.facets.getValue(saints)
        // Anonymous: no author named
        assertNull(facets["author"])
        assertEquals(listOf("teen"), facets["rating"])
        assertEquals(listOf("Gen"), facets["category"])
        assertEquals(listOf("Creator Chose Not To Use Archive Warnings"), facets["warning"])
        assertEquals(listOf("Fullmetal Alchemist - All Media Types", "Fullmetal Alchemist: Brotherhood & Manga"), facets["fandom"])
        // A relationship is one whatever the order of its names; a character is itself
        val pairings = facets["pairing"].orEmpty()
        assertTrue("edward elric & ling yao" in pairings && "edward elric & roy mustang" in pairings, "$pairings")
        assertTrue("roy mustang" in pairings, "$pairings")
        assertTrue("angst" in facets["tag"].orEmpty())
        assertEquals(listOf(Books.Status.IN_PROGRESS.key), facets["status"])
        val numbers = context.numbers.getValue(saints)
        assertEquals(4.0, numbers["parts"])
        assertTrue(numbers.getValue("words") > 6000, "$numbers")
        val texts = context.texts.getValue(saints)
        assertTrue(texts.getValue("annotation").startsWith("Ling Yao has been stuck in a basement"))
        assertTrue(texts.getValue("notes").startsWith("Heed the tags"))
        assertTrue(texts.getValue("pairingsLine").startsWith("Relationships: Edward Elric & Ling Yao"), texts.getValue("pairingsLine"))
        val parts = context.parts.getValue(saints)
        assertEquals(4, parts.values.count { it.content != null }, "${parts.keys}")
        val first = parts.getValue("250718281")
        assertEquals("Argument", first.title)
        assertEquals(0, first.position)
        assertTrue(first.content!!.startsWith("Ling sat in a chair"), first.content!!.take(80))
    }

    @Test
    fun `a chapter's page gives its text, the others named by the menu of chapters`() {
        val context = MemorySourceContext()
        capture(context, "https://archiveofourown.org/works/$saints/chapters/250718281", "work.html")
        val parts = context.parts.getValue(saints)
        assertEquals(setOf("250718281", "251408676", "251792311", "252456561"), parts.keys)
        assertTrue(parts.getValue("250718281").content != null)
        assertNull(parts.getValue("251408676").content)
        assertEquals("Nothing", parts.getValue("251408676").title)
        // The table of contents adds the day every chapter came out
        capture(context, "https://archiveofourown.org/works/$saints/navigate", "navigate.html")
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), context.parts.getValue(saints).getValue("251408676").publishedAt)
    }

    @Test
    fun `a work of one chapter keeps its text as its only part`() {
        val context = MemorySourceContext()
        assertEquals(listOf("94558111"), capture(context, "https://archiveofourown.org/works/94558111", "oneshot.html"))
        val parts = context.parts.getValue("94558111")
        assertEquals(setOf(Ao3.ONLY_PART), parts.keys)
        assertTrue(parts.getValue(Ao3.ONLY_PART).content.orEmpty().isNotBlank())
        assertEquals(listOf(Books.Status.FINISHED.key), context.facets.getValue("94558111")["status"])
    }

    @Test
    fun `the comments shown are the readers' opinions of the work`() {
        val context = MemorySourceContext()
        capture(context, "https://archiveofourown.org/works/89821571/chapters/238641631?show_comments=true&view_adult=true", "comments.html")
        val reviews = context.reviews.getValue("89821571")
        val first = reviews.getValue("1278250446")
        assertEquals("Vestrais", first.author)
        assertTrue(first.content.startsWith("Ah GreedLing"), first.content)
        assertEquals(Instant.parse("2026-08-03T22:41:00Z"), first.postedAt)
        assertTrue(reviews.size > 1)
    }

    @Test
    fun `the warning before an adult work and a page of no work give nothing`() {
        val context = MemorySourceContext()
        val warning = """
            <html><body><div id="main" class="works-show">
            <p class="caution">This work could have adult content. If you continue, you have agreed that you are willing to see such content.</p>
            <ul class="actions"><li><a href="/works/74280956?view_adult=true">Yes, Continue</a></li></ul>
            </div></body></html>
        """.trimIndent()
        assertEquals(emptyList(), source.capture(CapturedPage("https://archiveofourown.org/works/74280956", warning, now), context))
        assertTrue(context.heads.isEmpty())
    }

    @Test
    fun `the user's own marks are read of a page shown to them alone`() {
        val context = MemorySourceContext()
        // The whole work as a logged-in user sees it: kudos left, bookmarked, subscribed, marked for later
        val html = page("full.html")
            .replace("<header id=\"header\"", "<p id=\"greeting\"><a href=\"/users/Reader\">Hi, Reader!</a></p><header id=\"header\"")
            .replace("<p class=\"kudos\">", "<p class=\"kudos\"><a href=\"/users/Reader\">Reader</a>, ")
            .replace("<ul class=\"work navigation actions\">", "<ul class=\"work navigation actions\"><li class=\"bookmark\"><a href=\"#bookmark-form\">Edit Bookmark</a></li><li class=\"subscribe\"><form><input type=\"submit\" value=\"Unsubscribe\"></form></li><li class=\"mark\"><a href=\"#\">Mark as Read</a></li>")
        source.capture(CapturedPage("https://archiveofourown.org/works/$saints?view_full_work=true", html, now), context)
        assertEquals(Books.YES.key, context.signalValues[saints to Books.LIKED.key])
        assertEquals(Books.YES.key, context.signalValues[saints to Ao3.SIGNAL_BOOKMARKED])
        assertEquals(Books.YES.key, context.signalValues[saints to Ao3.SIGNAL_SUBSCRIBED])
        assertEquals(Books.YES.key, context.signalValues[saints to Ao3.SIGNAL_LATER])
        // To anyone else the page tells nothing of them
        val other = MemorySourceContext()
        source.capture(CapturedPage("https://archiveofourown.org/works/$saints?view_full_work=true", page("full.html"), now), other)
        assertTrue(other.signalValues.isEmpty())
    }
}
