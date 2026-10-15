package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FicbookPagesTest {

    private val source = Ficbook()
    private val now = Instant.parse("2026-10-06T10:00:00Z")
    private val work = "019fc2ac-ce89-7afa-aab8-b51d74fc50f7"

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, name: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return source.capture(CapturedPage(url, page(name), now), context)
    }

    @Test
    fun `the site's recommendations are a list of cards, every page of it`() {
        // The same cards as a fandom's list (article.fanfic-inline), checked on the page itself
        listOf("https://ficbook.net/recommendation", "https://ficbook.net/recommendation?p=2").forEach { url ->
            assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        }
    }

    @Test
    fun `the cards of a list give the works, the promoted ones left out`() {
        val context = MemorySourceContext()
        assertEquals(listOf(work, "9876543"), capture(context, "https://ficbook.net/fanfiction/no_fandom/originals", "fandom-originals.html"))
        assertEquals("Нас связали шрамами. Тихий дом (Том 1)", context.heads.getValue(work).title)
        assertEquals(Instant.parse("2026-08-03T21:00:00Z"), context.heads.getValue(work).updatedAt)
        val facets = context.facets.getValue(work)
        assertEquals(listOf("019492f9-69b1-7040-8a90-9de7e3cbb01e"), facets["author"])
        assertEquals(listOf("no_fandom/originals"), facets["fandom"])
        assertEquals(listOf("original"), facets["kind"])
        assertEquals(listOf("mixed"), facets["direction"])
        assertEquals(listOf("NC-17"), facets["rating"])
        assertEquals(listOf("finished"), facets["status"])
        assertEquals(listOf("28510"), facets["series"])
        assertTrue("слоуберн" in facets.getValue("tag"))
        // Every entry of the line in one field: a pairing one whatever the order of its names, a
        // character alone as itself; the names stay as written
        assertEquals(listOf("авелин/брайан", "альфред/итан", "фрида", "сия", "терен", "бабушка рико"), facets["pairing"])
        assertEquals("Брайан/Авелин", context.facetNames.getValue("pairing")["авелин/брайан"])
        assertEquals("Бабушка Рико", context.facetNames.getValue("pairing")["бабушка рико"])
        assertNull(facets["character"])
        assertTrue(context.texts.getValue(work).getValue(Ficbook.PAIRINGS_LINE).startsWith("Брайан/Авелин"))
        val numbers = context.numbers.getValue(work)
        assertEquals(mapOf("likes" to 2.0, "pages" to 180.0, "words" to 68967.0, "parts" to 20.0), numbers)
        assertTrue(context.texts.getValue(work).getValue("annotation").startsWith("Молодой хирург"))
        assertEquals(listOf("rouling_dzhoan___garri_potter".let { "books/$it" }, "anime_and_manga/naruto"), context.facets.getValue("9876543")["fandom"])
        assertEquals(listOf("fanfiction"), context.facets.getValue("9876543")["kind"])
        assertEquals(Instant.parse("2026-08-02T14:55:00Z"), context.heads.getValue("9876543").updatedAt)
    }

    @Test
    fun `a work's page gives its header and table of contents`() {
        val context = MemorySourceContext()
        assertEquals(listOf(work), capture(context, "https://ficbook.net/readfic/$work", "readfic.html"))
        val texts = context.texts.getValue(work)
        assertEquals("Я вынашивала эту историю в своей голове около 20-ти лет.", texts["notes"])
        assertEquals("Посвящаю эту работу моему любимому дедушке.", texts["dedication"])
        assertEquals(listOf("019492f9-69b1-7040-8a90-9de7e3cbb01e"), context.facets.getValue(work)["author"])
        assertEquals(2.0, context.numbers.getValue(work)["comments"])
        val parts = context.parts.getValue(work)
        assertEquals(listOf("42501445", "42505773"), parts.keys.toList())
        assertEquals("1. Печенье и лекарства", parts.getValue("42501445").title)
        assertNull(parts.getValue("42501445").content)
        // The promoted work of the page is not one of the user's
        assertEquals(setOf(work), context.heads.keys)
    }

    /** A work's page as the site shows it to a user logged in ([loggedIn]): the buttons of its header as they are on the site, pressed or not. */
    private fun marked(loggedIn: Boolean, liked: Boolean, followed: Boolean, read: Boolean) = """
        <html><body>
        ${if (loggedIn) """<a href="/home/liked_fanfics">Понравившиеся работы</a>""" else ""}
        <h1 class="heading">Работа</h1>
        <section class="fanfic-hat"><div class="hat-actions-container"><div class="d-flex flex-wrap gap-8">
          <button type="button" class="ds-btn ds-btn-primary ds-btn-mini${if (liked) " ds-btn-success-filled" else ""} jsVueComponent"><svg class="ic_thumbs-up svg-icon"></svg><span>8</span></button>
          <button type="button" class="ds-btn ds-btn-primary ds-btn-mini${if (followed) " ds-btn-success-filled" else ""} jsVueComponent"><svg class="ic_star-empty svg-icon"></svg> 4</button>
        </div>
        <button type="button" class="ds-btn ds-btn-regular ds-btn-mini${if (read) " ds-btn-success" else ""} jsVueComponent"><svg class="${if (read) "ic_checkbox-checked2" else "ic_checkbox-unchecked2"} svg-icon"></svg> Прочитано</button>
        </div></section>
        </body></html>
    """.trimIndent()

    @Test
    fun `the user's marks of a work are its signals, read only of a user logged in`() {
        val context = MemorySourceContext()
        source.capture(CapturedPage("https://ficbook.net/readfic/123", marked(loggedIn = true, liked = true, followed = false, read = true), now), context)
        assertEquals(mapOf("123" to "yes"), context.signals.withSignal("liked"))
        assertEquals(mapOf("123" to "yes"), context.signals.withSignal("read"))
        assertEquals(emptyMap(), context.signals.withSignal(Ficbook.SIGNAL_FOLLOWED))
        // Unread and unliked again: the signals go
        source.capture(CapturedPage("https://ficbook.net/readfic/123", marked(loggedIn = true, liked = false, followed = true, read = false), now), context)
        assertEquals(emptyMap(), context.signals.withSignal("liked"))
        assertEquals(emptyMap(), context.signals.withSignal("read"))
        assertEquals(mapOf("123" to "yes"), context.signals.withSignal(Ficbook.SIGNAL_FOLLOWED))
        // A page of no user says nothing of the marks: they stay
        source.capture(CapturedPage("https://ficbook.net/readfic/123", marked(loggedIn = false, liked = false, followed = false, read = false), now), context)
        assertEquals(mapOf("123" to "yes"), context.signals.withSignal(Ficbook.SIGNAL_FOLLOWED))
    }

    @Test
    fun `a part gives its text, and comments are the readers' opinions`() {
        val context = MemorySourceContext()
        capture(context, "https://ficbook.net/readfic/$work", "readfic.html")
        assertEquals(listOf(work), capture(context, "https://ficbook.net/readfic/$work/42501445#part_content", "part.html"))
        val part = context.parts.getValue(work).getValue("42501445")
        assertEquals(0, part.position)
        assertEquals(
            "Дорога в небольшом поселении на пару сотен человек серая, каменистая и неприятная.\nНо еще неприятнее была боль, создавшая в горле ком.\n\n— Доброе утро, — сказал он.",
            part.content,
        )

        capture(context, "https://ficbook.net/readfic/$work/comments", "comments.html")
        val comments = context.reviews.getValue(work)
        assertEquals(setOf("138019862", "138000001"), comments.keys)
        // A quote of another comment is not this one's words
        assertEquals("Спасибо-спасибо-спасибо! Я очень рада, что вам понравилось) Приятного чтения!", comments.getValue("138019862").content)
        assertEquals(Instant.parse("2026-09-01T09:15:00Z"), comments.getValue("138019862").postedAt)
    }

    @Test
    fun `a tag's own page says what the tag means, the tag known by its name as the works write it`() {
        val context = MemorySourceContext()
        assertEquals(emptyList(), capture(context, "https://ficbook.net/tags/1682", "tag.html"))
        assertEquals("Hurt/Comfort", context.facetNames.getValue("tag")["hurt/comfort"])
        assertEquals(
            "Работа сосредоточена на эмоциональных и/или физических страданиях одних персонажей и заботе о них со стороны других персонажей.",
            context.facetDescriptions.getValue("tag")["hurt/comfort"],
        )
    }

    @Test
    fun `a work's cover is read of its header, as the site sends it and as the browser draws it`() {
        val url = "https://ficbook.net/readfic/$work"
        val covers = "https://assets.teinon.net/fanfic-covers"
        fun withCover(html: String) = Jsoup.parse(page("readfic.html")).also { it.selectFirst(".fanfic-hat")!!.prependChild(Jsoup.parse(html).body().child(0)) }.outerHtml()
        val sent = MemorySourceContext()
        source.capture(CapturedPage(url, withCover("""<fanfic-cover class="jsVueComponent" src-original="$covers/X.webp" src-desktop="$covers/d_X.webp" src-mobile="$covers/m_X.webp"></fanfic-cover>"""), now), sent)
        assertEquals(listOf("$covers/d_X.webp"), sent.pictures[work])
        val drawn = MemorySourceContext()
        val picture = """<div class="jsVueComponent"><div class="fanfic-hat-cover"><picture class="fanfic-hat-cover-picture">""" +
            """<source media="(max-width: 650px)" srcset="$covers/m_X.webp"><img src="$covers/d_X.webp" alt="" width="200"></picture></div></div>"""
        source.capture(CapturedPage(url, withCover(picture), now), drawn)
        assertEquals(listOf("$covers/d_X.webp"), drawn.pictures[work])
        // The extension marks the cover of the header, not the covers of the other works' cards
        assertEquals(listOf("$covers/d_X.webp"), Jsoup.parse(withCover(picture)).select(source.pageDecor.pictures!!.selector).map { it.attr("src") })
        // A work without a cover has none
        assertNull(MemorySourceContext().also { source.capture(CapturedPage(url, page("readfic.html"), now), it) }.pictures[work])
    }

    @Test
    fun `a work's page without its header changes nothing, a block of tags of a changed markup leaves the tags`() {
        val context = MemorySourceContext()
        val url = "https://ficbook.net/readfic/$work"
        capture(context, url, "readfic.html")
        val title = context.heads.getValue(work).title
        val texts = context.texts.getValue(work).toMap()
        val tags = context.facets.getValue(work).getValue("tag")
        assertTrue(texts.isNotEmpty() && tags.isNotEmpty())

        // An error page with a heading: no word of the work
        val noHeader = Jsoup.parse(page("readfic.html")).also { it.select(".fanfic-hat").remove() }.outerHtml()
        assertEquals(emptyList(), source.capture(CapturedPage(url, noHeader, now), context))
        assertEquals(title, context.heads.getValue(work).title)
        assertEquals(texts, context.texts.getValue(work).toMap())
        assertEquals(tags, context.facets.getValue(work).getValue("tag"))

        // The block of tags there, its links of another markup: the tags are not taken away
        val otherTags = Jsoup.parse(page("readfic.html")).also { d -> d.select("a.tag").forEach { it.tagName("span").removeClass("tag") } }.outerHtml()
        source.capture(CapturedPage(url, otherTags, now), context)
        assertEquals(tags, context.facets.getValue(work).getValue("tag"))
    }
}
