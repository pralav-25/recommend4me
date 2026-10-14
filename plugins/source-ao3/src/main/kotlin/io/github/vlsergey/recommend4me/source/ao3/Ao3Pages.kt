package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.BASE
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.BOOKMARKS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.CATEGORY
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.COMMENTS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.HITS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.KUDOS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.LANGUAGE
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.NOTES
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.ONLY_PART
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.PARTS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.RATING
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.SERIES
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.SIGNAL_BOOKMARKED
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.SIGNAL_LATER
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.SIGNAL_SUBSCRIBED
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.STATUS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.TAG
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.WORDS
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads the pages of archiveofourown.org and writes what they say: the cards of a list (a tag, a
 * search, a user's works and bookmarks, a series, a collection, the history), a work's header with
 * its chapters, the table of contents, the comments shown.
 */
class Ao3Pages(private val context: SourceContext) {

    private val items = context.items

    fun read(page: CapturedPage): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        val path = runCatching { URI(page.url).path }.getOrDefault("")
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt)
        WORK.matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            when (m.groupValues[2]) {
                "navigate" -> if (contents(doc, id)) touched += id
                else -> work(doc, id, page.capturedAt)?.let { touched += it }
            }
            if (comments(doc, id)) touched += id
        }
        return touched.toList()
    }

    // --- Cards of a list ---

    private fun cards(doc: Document, now: Instant): List<String> = doc.select("li.blurb").mapNotNull { card ->
        val link = card.selectFirst("h4.heading a[href^=/works/]") ?: return@mapNotNull null
        val id = WORK.matchEntire(link.attr("href").substringBefore('?').substringBefore('#'))?.groupValues?.get(1) ?: return@mapNotNull null
        val title = link.text().trim().ifEmpty { return@mapNotNull null }
        items.upsert(ItemHead(id, "$BASE/works/$id", title, updatedAt = card.selectFirst("p.datetime")?.text()?.let(::cardDate)), now)
        authors(id, card.select("h4.heading a[rel=author]"))
        fandoms(id, card.select("h5.fandoms a.tag"))
        card.selectFirst("ul.required-tags span.rating")?.attr("title")?.let { rating(id, it) }
        card.selectFirst("ul.required-tags span.category")?.attr("title")?.let { written ->
            category(id, written.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }
        card.selectFirst("ul.required-tags span.iswip")?.let { s ->
            val status = if (s.hasClass("complete-yes")) Books.Status.FINISHED else Books.Status.IN_PROGRESS
            items.setFacet(id, STATUS, listOf(FacetValue(status.key, status.modelName)))
        }
        // The work's own tags, not those of the bookmark the user wrote under it
        val tags = card.children().filter { it.tagName() == "ul" && it.hasClass("tags") }
        fun tagsOf(kind: String) = tags.flatMap { it.select("li.$kind a.tag") }
        tagged(id, tagsOf("warnings"), tagsOf("relationships"), tagsOf("characters"), tagsOf("freeforms"))
        card.children().firstOrNull { it.tagName() == "blockquote" && it.hasClass("summary") }?.let {
            items.setTexts(id, mapOf(ANNOTATION to PageText.of(it)))
        }
        card.children().firstOrNull { it.tagName() == "ul" && it.hasClass("series") }?.selectFirst("a[href^=/series/]")?.let { series(id, it) }
        card.children().firstOrNull { it.tagName() == "dl" && it.hasClass("stats") }?.let { stats(id, it) }
        id
    }

    // --- A work's page ---

    /**
     * A work's page — its first chapter, another, or the whole work: the header and every chapter
     * shown. None for a page without the header: the warning before an adult work, a work locked
     * to the site's users.
     */
    private fun work(doc: Document, id: String, now: Instant): String? {
        val meta = doc.selectFirst("dl.work.meta") ?: return null
        val preface = doc.selectFirst("#workskin > div.preface.group")
        val title = preface?.selectFirst("h2.title")?.text()?.trim()?.ifEmpty { null } ?: return null
        val stats = meta.selectFirst("dl.stats")
        val updated = stats?.selectFirst("dd.status")?.text()?.let(::isoDate) ?: stats?.selectFirst("dd.published")?.text()?.let(::isoDate)
        items.upsert(ItemHead(id, "$BASE/works/$id", title, updatedAt = updated), now)
        authors(id, preface.select("h3.byline a[rel=author]"))
        fandoms(id, meta.select("dd.fandom a.tag"))
        meta.selectFirst("dd.rating a.tag")?.text()?.let { rating(id, it.trim()) }
        meta.select("dd.category a.tag").map { it.text().trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { category(id, it) }
        tagged(id, meta.select("dd.warning a.tag"), meta.select("dd.relationship a.tag"), meta.select("dd.character a.tag"), meta.select("dd.freeform a.tag"))
        meta.selectFirst("dd.series span.position a[href^=/series/]")?.let { series(id, it) }
        stats?.let { stats(id, it) }
        meta.selectFirst("dd.language")?.let(::language)?.let { items.setFacet(id, LANGUAGE, listOf(it)) }
        // A section of the preface the work has not: none; one whose text is not found: its markup changed, left as it is
        fun text(section: String): Pair<Boolean, String?> {
            val found = preface.selectFirst(section) ?: return true to null
            return found.selectFirst("blockquote.userstuff")?.let { true to PageText.of(it) } ?: (false to null)
        }
        items.setTexts(
            id,
            listOf(ANNOTATION to text(".summary"), NOTES to text(".notes"))
                .filter { (_, read) -> read.first }.associate { (key, read) -> key to read.second },
        )
        marks(doc, id)
        chapters(doc, id, title)
        return id
    }

    /**
     * The chapters: every one the menu of the chapters names, without its text, and the text of
     * every chapter the page shows. A work of one chapter names none: its text is its only part.
     */
    private fun chapters(doc: Document, id: String, title: String) {
        val named = doc.select("#chapter_index option").mapIndexed { i, o ->
            PartData(o.attr("value"), i, NUMBERED.replace(o.text().trim(), ""), null)
        }.filter { it.id.isNotEmpty() }
        val position = named.associate { it.id to it.position }
        val shown = doc.select("#chapters > div.chapter[id^=chapter-]").mapNotNull { chapter ->
            val heading = chapter.selectFirst("div.chapter.preface h3.title") ?: return@mapNotNull null
            val chapterId = heading.selectFirst("a[href]")?.attr("href")?.let { CHAPTER.find(it)?.groupValues?.get(1) } ?: return@mapNotNull null
            val text = chapter.selectFirst("div.userstuff[role=article]")?.let { PageText.of(it, NOT_THE_TEXT) }?.ifBlank { null } ?: return@mapNotNull null
            val number = chapter.id().removePrefix("chapter-").toIntOrNull()
            PartData(chapterId, position[chapterId] ?: number?.minus(1) ?: -1, chapterTitle(heading.text()), text)
        }
        val parts = named.filter { n -> shown.none { it.id == n.id } } + shown
        if (parts.isNotEmpty()) {
            items.saveParts(id, parts)
            return
        }
        doc.selectFirst("#chapters > div.userstuff")?.let { PageText.of(it, NOT_THE_TEXT) }?.takeIf { it.isNotBlank() }?.let { text ->
            items.saveParts(id, listOf(PartData(ONLY_PART, 0, title, text)))
        }
    }

    /** The table of contents (/works/<id>/navigate): every chapter with the day it came out. */
    private fun contents(doc: Document, id: String): Boolean {
        if (items.find(id) == null) return false
        val parts = doc.select("ol.chapter.index li").mapIndexedNotNull { i, li ->
            val a = li.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val chapterId = CHAPTER.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val day = li.selectFirst("span.datetime")?.text()?.trim()?.removePrefix("(")?.removeSuffix(")")
            PartData(chapterId, i, NUMBERED.replace(a.text().trim(), ""), null, day?.let(::isoDate))
        }
        if (parts.isEmpty()) return false
        items.saveParts(id, parts)
        return true
    }

    /**
     * The user's own marks of the work — kudos left, a bookmark, a subscription, marked for later —
     * read only of a page shown to a user logged in: the greeting names them.
     */
    private fun marks(doc: Document, id: String) {
        val me = doc.selectFirst("#greeting a[href^=/users/]")?.attr("href")?.let { USER.find(it)?.groupValues?.get(1) } ?: return
        val yes = Books.YES.key
        doc.selectFirst("#kudos")?.let { kudos ->
            val left = kudos.select("a[href]").any { it.attr("href") == "/users/$me" }
            // A long list is cut short: the user's name missing from it says nothing
            if (left || kudos.selectFirst("#kudos_more_link") == null) context.signals.set(id, Books.LIKED.key, yes.takeIf { left })
        }
        doc.selectFirst("ul.work.navigation li.bookmark a")?.text()?.let { context.signals.set(id, SIGNAL_BOOKMARKED, yes.takeIf { _ -> it.startsWith("Edit") }) }
        doc.selectFirst("ul.work.navigation li.subscribe input[type=submit]")?.attr("value")?.let {
            context.signals.set(id, SIGNAL_SUBSCRIBED, yes.takeIf { _ -> it.startsWith("Unsubscribe") })
        }
        doc.selectFirst("ul.work.navigation li.mark a")?.text()?.let { context.signals.set(id, SIGNAL_LATER, yes.takeIf { _ -> it.startsWith("Mark as Read") }) }
    }

    // --- Comments ---

    /** The comments the page shows, of the work or of its chapter. */
    private fun comments(doc: Document, id: String): Boolean {
        val list = doc.select("li.comment[id^=comment_]")
        if (list.isEmpty() || items.find(id) == null) return false
        items.saveReviews(id, list.mapNotNull { c ->
            val text = c.children().firstOrNull { it.tagName() == "blockquote" && it.hasClass("userstuff") }?.let(PageText::of)?.ifBlank { null }
                ?: return@mapNotNull null
            val byline = c.selectFirst("h4.byline")
            ReviewData(
                c.id().removePrefix("comment_"),
                byline?.selectFirst("a[href^=/users/]")?.text()?.trim() ?: byline?.ownText()?.trim()?.ifEmpty { null },
                null,
                text,
                byline?.selectFirst("span.posted.datetime")?.let(::commentDate),
            )
        })
        return true
    }

    // --- What a card and a header both tell ---

    private fun authors(id: String, links: List<Element>) {
        val authors = links.mapNotNull { a ->
            // A user's pseudonym is the user: "/users/<login>/pseuds/<pseud>"
            USER.find(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, a.text().trim()) }
        }.distinctBy { it.key }
        if (authors.isNotEmpty()) items.setFacet(id, AUTHOR, authors)
    }

    private fun fandoms(id: String, links: List<Element>) {
        val fandoms = links.map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        if (fandoms.isEmpty()) return
        items.setFacet(id, FANDOM, fandoms.map { FacetValue(it, it) })
        val original = fandoms.all { it == ORIGINAL_WORK }
        items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(if (original) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION)))
    }

    /** The categories as the standard directions, shared with ficbook's; "No category" is none. */
    private fun category(id: String, written: List<String>) {
        items.setFacet(id, CATEGORY, written.mapNotNull { CATEGORIES[it] }.distinct().map { FacetValue(it.key, it.modelName) })
    }

    private fun rating(id: String, written: String) {
        val key = RATINGS[written] ?: written.lowercase()
        items.setFacet(id, RATING, listOf(FacetValue(key, written)))
    }

    /**
     * The tags of the work: the relationships and the characters — one field, as written, and the
     * line of them the universes are worked out from — the warnings and the other tags, one field of
     * the site's tags (the user may ignore the warnings as any tag). A kind the page does not show is
     * left as it was: the user may hide the warnings and the other tags.
     */
    private fun tagged(id: String, warnings: List<Element>, relationships: List<Element>, characters: List<Element>, freeforms: List<Element>) {
        fun names(links: List<Element>) = links.map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val pairings = names(relationships)
        val people = names(characters)
        if (pairings.isNotEmpty() || people.isNotEmpty()) {
            items.setFacet(id, PAIRING, (pairings + people).map(::entry).distinctBy { it.key })
            val line = listOfNotNull(
                pairings.takeIf { it.isNotEmpty() }?.joinToString(", ", "Relationships: "),
                people.takeIf { it.isNotEmpty() }?.joinToString(", ", "Characters: "),
            ).joinToString("\n")
            items.setTexts(id, mapOf(PAIRINGS_LINE to line))
        }
        val shownWarnings = names(warnings)
        val shownOthers = names(freeforms)
        if (shownWarnings.isEmpty() && shownOthers.isEmpty()) return
        // The kind not shown kept as it was: the warnings are the site's few, told from the other tags by their keys
        val before = if (shownWarnings.isEmpty() || shownOthers.isEmpty()) items.facets(id)[TAG].orEmpty() else emptyList()
        fun kept(warning: Boolean) = before.filter { (it in WARNINGS) == warning }.map { FacetValue(it) }
        val values = (if (shownWarnings.isEmpty()) kept(true) else shownWarnings.map { FacetValue(it.lowercase(), it) }) +
            (if (shownOthers.isEmpty()) kept(false) else shownOthers.map { FacetValue(it.lowercase(), it) })
        items.setFacet(id, TAG, values.distinctBy { it.key })
    }

    private fun series(id: String, a: Element) {
        val key = a.attr("href").removePrefix("/series/").substringBefore('?').substringBefore('/')
        if (key.isNotEmpty()) items.setFacet(id, SERIES, listOf(FacetValue(key, a.text().trim())))
    }

    /** The numbers of a card's or a header's statistics, and the status the chapters tell: "4/7" and "4/?" are in progress. */
    private fun stats(id: String, dl: Element) {
        fun count(name: String) = dl.selectFirst("dd.$name")?.text()?.let(::number)
        val chapters = dl.selectFirst("dd.chapters")?.text()?.trim()
        items.setNumbers(
            id,
            listOfNotNull(
                count("words")?.let { WORDS to it },
                chapters?.substringBefore('/')?.let(::number)?.let { PARTS to it },
                count("kudos")?.let { KUDOS to it },
                count("bookmarks")?.let { BOOKMARKS to it },
                count("hits")?.let { HITS to it },
                count("comments")?.let { COMMENTS to it },
            ).toMap(),
        )
        chapters?.split('/')?.takeIf { it.size == 2 }?.let { (done, planned) ->
            val status = if (planned.trim() == done.trim()) Books.Status.FINISHED else Books.Status.IN_PROGRESS
            items.setFacet(id, STATUS, listOf(FacetValue(status.key, status.modelName)))
        }
        dl.selectFirst("dd.language")?.let(::language)?.let { items.setFacet(id, LANGUAGE, listOf(it)) }
    }

    companion object {
        /** A work's address: "/works/<id>", "/works/<id>/chapters/<chapter>", "/works/<id>/navigate". */
        private val WORK = Regex("/works/(\\d+)(?:/chapters/\\d+|/(navigate))?/?")
        private val CHAPTER = Regex("/works/\\d+/chapters/(\\d+)")
        private val USER = Regex("^/users/([^/?#]+)")

        /** The number of a chapter in a menu and a table of contents: "1. Argument". */
        private val NUMBERED = Regex("^\\d+\\.\\s*")

        /** The fandom of the original works. */
        private const val ORIGINAL_WORK = "Original Work"

        /** The ratings by their names, to the keys the cards' symbols have (rating-<key>). */
        /** The keys of the site's warnings among its tags: the archive's own few, in every name they had. */
        private val WARNINGS = setOf(
            "no archive warnings apply", "creator chose not to use archive warnings", "graphic depictions of violence",
            "major character death", "rape/non-con", "underage", "underage sex",
        )

        private val CATEGORIES = mapOf(
            "Gen" to Books.Direction.GEN, "F/M" to Books.Direction.HET, "M/M" to Books.Direction.SLASH,
            "F/F" to Books.Direction.FEMSLASH, "Multi" to Books.Direction.MIXED, "Other" to Books.Direction.OTHER,
        )

        private val RATINGS = mapOf(
            "General Audiences" to "general-audience", "Teen And Up Audiences" to "teen", "Mature" to "mature",
            "Explicit" to "explicit", "Not Rated" to "notrated",
        )

        /** What the text of a chapter holds that is not the text: the heading "Chapter Text", scripts. */
        private const val NOT_THE_TEXT = "h3.landmark, script, style"

        private val CARD_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        private val COMMENT_TIME = DateTimeFormatter.ofPattern("d MMM yyyy h:mma", Locale.ENGLISH)

        /** "6,284" → 6284. */
        private fun number(text: String): Double? = Regex("\\d[\\d,]*").find(text)?.value?.replace(",", "")?.toDoubleOrNull()

        /** "2026-10-13" — the site's days are of no zone given: taken as UTC. */
        private fun isoDate(text: String): Instant? = runCatching { LocalDate.parse(text.trim()).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()

        /** "13 Oct 2026". */
        private fun cardDate(text: String): Instant? = runCatching { LocalDate.parse(text.trim(), CARD_DATE).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()

        /** "Mon 03 Aug 2026 10:41PM UTC", from its parts. */
        private fun commentDate(posted: Element): Instant? {
            val day = posted.selectFirst("span.date")?.text() ?: return null
            val month = posted.selectFirst("abbr.month")?.text() ?: return null
            val year = posted.selectFirst("span.year")?.text() ?: return null
            val time = posted.selectFirst("span.time")?.text() ?: return null
            return runCatching { LocalDateTime.parse("$day $month $year $time", COMMENT_TIME).toInstant(ZoneOffset.UTC) }.getOrNull()
        }

        /** "Chapter 1: Argument" → "Argument"; "Chapter 2" stays. */
        private fun chapterTitle(heading: String): String = heading.trim().let { t -> Regex("^Chapter \\d+:\\s*(.+)$").find(t)?.groupValues?.get(1) ?: t }

        private fun language(dd: Element): FacetValue? {
            val name = dd.text().trim().ifEmpty { return null }
            return FacetValue(dd.attr("lang").ifEmpty { name.lowercase() }, name)
        }

        /**
         * An entry of the relationships and characters as written — a romantic "A/B", a platonic
         * "A & B", a character alone: one whatever the order of its names — the key has them sorted.
         */
        private fun entry(written: String): FacetValue {
            val separator = if ('/' in written) "/" else if (" & " in written) " & " else null
            val key = separator?.let { s -> written.split(s).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString(s) }
                ?: written.trim().lowercase()
            return FacetValue(key, written)
        }
    }
}
