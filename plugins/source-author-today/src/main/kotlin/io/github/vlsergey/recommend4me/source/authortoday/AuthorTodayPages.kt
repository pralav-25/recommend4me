package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.AWARDS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.BASE
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.CHARS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.COMMENTS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.FORM
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.GENRE
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.LIKES
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.NOTES
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.REVIEWS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.SERIES
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.STATUS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.TAG
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.VIEWS
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant

/**
 * Reads the pages of author.today and writes what they say. Every page with cards of works — a
 * genre, a search, a library, a series, an author's works — gives those works as the cards show
 * them; a work's page gives the rest of the work; a page of reviews its reviews; the reader the
 * text of a chapter. A page the parser does not know gives the cards it has, if any.
 */
class AuthorTodayPages(private val context: SourceContext) {

    private val items = context.items

    fun read(page: CapturedPage): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        val path = runCatching { URI(page.url).path }.getOrDefault("")
        // The user's own marks are shown only to a user who is logged in: a guest's page says nothing of them
        val loggedIn = LOGGED_IN.find(page.html)?.groupValues?.get(1) == "true"
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt, loggedIn)
        WORK.matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            when (m.groupValues[2]) {
                "" -> work(doc, page.html, id, page.capturedAt, loggedIn)?.let { touched += it }
                "/reviews" -> if (reviewList(doc, id)) touched += id
            }
        }
        REVIEW.matchEntire(path)?.let { m -> review(doc, m.groupValues[1])?.let { touched += it } }
        READER.matchEntire(path)?.let { m -> reader(doc, m.groupValues[1], m.groupValues[2], page.capturedAt)?.let { touched += it } }
        return touched.toList()
    }

    // --- Cards of a list ---

    /** Every card of a work on the page. */
    private fun cards(doc: Document, now: Instant, loggedIn: Boolean): List<String> = doc.select(".book-row").mapNotNull { card ->
        val link = card.selectFirst(".book-title a[href^=/work/]") ?: return@mapNotNull null
        val id = WORK_ID.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
        val title = link.text().trim().ifEmpty { return@mapNotNull null }
        items.upsert(ItemHead(id, "$BASE/work/$id", title, updatedAt = updatedAt(card)), now)
        authors(card.select(".book-author a[href^=/u/]")).takeIf { it.isNotEmpty() }?.let { items.setFacet(id, AUTHOR, it) }
        genresIn(card.select(".book-genres a[href^=/work/genre/]")).let { (form, genres) -> writeGenres(id, form, genres) }
        status(card)?.let { items.setFacet(id, STATUS, listOf(it)) }
        series(card)?.let { items.setFacet(id, SERIES, listOf(it)) }
        items.setNumbers(
            id,
            listOfNotNull(
                size(card)?.let { CHARS to it },
                stat(card, "Просмотры")?.let { VIEWS to it },
                stat(card, "Понравилось")?.let { LIKES to it },
                stat(card, "Комментарии")?.let { COMMENTS to it },
                stat(card, "Рецензии")?.let { REVIEWS to it },
            ).toMap(),
        )
        card.selectFirst(".annotation")?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
        cover(card.selectFirst("img"))?.let { items.setPictures(id, listOf(it)) }
        if (loggedIn) library(card.selectFirst("library-button"))?.let { shelf(id, it) }
        id
    }

    /**
     * The shelf of the user's library the work is on — "None" when on none — as the standard one
     * (a shelf the standard does not know as the site names it), and of the shelf of the finished
     * the standard "read".
     */
    private fun shelf(id: String, shelf: String) {
        val standard = SHELVES[shelf]
        context.signals.set(id, Books.SHELF.key, standard?.key ?: shelf.takeIf { it != "None" })
        context.signals.set(id, Books.READ.key, Books.YES.key.takeIf { standard == Books.Shelf.FINISHED })
    }

    private fun writeGenres(id: String, form: FacetValue?, genres: List<FacetValue>) {
        form?.let { items.setFacet(id, FORM, listOf(it)) }
        if (genres.isNotEmpty()) {
            items.setFacet(id, GENRE, genres)
            // The site's genres "Фанфик", "Эротический фанфик" are what fan fiction is filed under, its
            // "Альтернативная история" our world with its history changed; a work shown with neither is an original one
            val kind = when {
                genres.any { it.key.startsWith(FANFICTION_GENRE) } -> UniverseFacets.FANFICTION
                genres.any { it.key == ALTERNATIVE_HISTORY_GENRE } -> UniverseFacets.ALTERNATIVE_HISTORY
                else -> UniverseFacets.ORIGINAL
            }
            items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(kind)))
        }
    }

    /** "/work/genre/all/any/novel" is the form (Роман); "/work/genre/<slug>" a genre. */
    private fun genresIn(links: List<Element>): Pair<FacetValue?, List<FacetValue>> {
        var form: FacetValue? = null
        val genres = ArrayList<FacetValue>()
        links.forEach { a ->
            val href = a.attr("href").substringBefore('?')
            val name = a.text().trim()
            if (href.startsWith("/work/genre/all/any/")) form = FacetValue(href.removePrefix("/work/genre/all/any/"), name)
            else href.removePrefix("/work/genre/").takeIf { it.isNotEmpty() && '/' !in it }?.let { genres += FacetValue(it, name) }
        }
        return form to genres
    }

    private fun authors(links: List<Element>): List<FacetValue> = links.mapNotNull { a ->
        val login = AUTHOR_LOGIN.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
        FacetValue(login, a.text().trim())
    }

    private fun status(e: Element): FacetValue? {
        val icon = e.selectFirst(".book-status-icon") ?: return null
        val text = icon.parent()?.text()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        // The standard status; another as the site writes it, for the application to tell of
        return STATUSES[text]?.let { FacetValue(it.key, it.modelName) } ?: FacetValue(text, text.replaceFirstChar { it.uppercase() })
    }

    private fun series(e: Element): FacetValue? =
        e.selectFirst("a[href^=/work/series/]")?.let { a ->
            FacetValue(a.attr("href").removePrefix("/work/series/").substringBefore('?'), a.text().trim())
        }

    /** The number of characters: "95 898 зн.". */
    private fun size(e: Element): Double? = e.selectFirst("[data-hint^=Размер]")?.text()?.let(PageText::number)?.toDouble()

    /** A counter of the card: "Просмотры · 14 811". */
    private fun stat(e: Element, name: String): Double? =
        e.selectFirst("[data-hint^=$name]")?.attr("data-hint")?.substringAfter('·')?.let(PageText::number)?.toDouble()

    /** When the work was updated or finished: the first moment the card names. */
    private fun updatedAt(e: Element): Instant? =
        e.select("[data-time]").firstOrNull { el ->
            val hint = el.attr("data-hint").lowercase()
            "обновлен" in hint || "заверш" in hint
        }?.attr("data-time")?.let(::instant)

    private fun cover(img: Element?): String? =
        img?.attr("src")?.takeIf { it.contains("/content/") }?.substringBefore('?')

    private fun library(e: Element?): String? = e?.attr("params")?.let { LIBRARY_STATE.find(it)?.groupValues?.get(1) }

    // --- A work's page ---

    private fun work(doc: Document, html: String, id: String, now: Instant, loggedIn: Boolean): String? {
        val panel = doc.selectFirst(".book-meta-panel") ?: return null
        val title = panel.selectFirst(".book-title")?.text()?.trim()?.ifEmpty { null } ?: return null
        items.upsert(ItemHead(id, "$BASE/work/$id", title, updatedAt = updatedAt(panel)), now)
        authors(panel.select(".book-authors a[href^=/u/]")).takeIf { it.isNotEmpty() }?.let { items.setFacet(id, AUTHOR, it) }
        val (form, genres) = genresIn(panel.select(".book-genres a[href^=/work/genre/]"))
        writeGenres(id, form, genres)
        status(panel)?.let { items.setFacet(id, STATUS, listOf(it)) }
        series(panel)?.let { items.setFacet(id, SERIES, listOf(it)) }
        items.setFacet(id, TAG, panel.select(".tags a").map { a ->
            val name = a.attr("title").ifBlank { a.text() }.trim()
            FacetValue(name.lowercase(), name)
        })
        val like = LIKE_BUTTON.find(html)
        items.setNumbers(
            id,
            listOfNotNull(
                size(panel)?.let { CHARS to it },
                stat(panel, "Просмотры")?.let { VIEWS to it },
                (like?.groupValues?.get(1)?.toDoubleOrNull() ?: panel.selectFirst(".like-count")?.text()?.let(PageText::number)?.toDouble())?.let { LIKES to it },
                doc.selectFirst("#commentTotalCount")?.text()?.let(PageText::number)?.takeIf { it > 0 }?.let { COMMENTS to it.toDouble() },
                doc.select("a[href=/work/$id/reviews]").firstNotNullOfOrNull { PageText.number(it.text()) }?.let { REVIEWS to it.toDouble() },
                panel.selectFirst(".btn-reward")?.text()?.let(PageText::number)?.let { AWARDS to it.toDouble() },
            ).toMap(),
        )
        val annotation = doc.select("#tab-annotation .annotation .rich-content")
        val notes = annotation.firstOrNull { it.selectFirst("p.text-primary")?.text()?.startsWith("Примечания") == true }
        items.setTexts(
            id,
            mapOf(
                ANNOTATION to annotation.firstOrNull { it != notes }?.let(PageText::of),
                NOTES to notes?.let { n -> PageText.of(n, "p.text-primary, script, style") },
            ),
        )
        val parts = doc.select("#tab-chapters .table-of-content li").mapIndexedNotNull { i, li ->
            val a = li.selectFirst("a[href^=/reader/]") ?: return@mapIndexedNotNull null
            val partId = READER.matchEntire(a.attr("href").substringBefore('?'))?.groupValues?.get(2) ?: return@mapIndexedNotNull null
            PartData(partId, i, a.text().trim(), null, li.selectFirst("[data-time]")?.attr("data-time")?.let(::instant))
        }
        if (parts.isNotEmpty()) items.saveParts(id, parts)
        (cover(doc.selectFirst(".book-cover img.cover-image")) ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { "/content/" in it })
            ?.let { items.setPictures(id, listOf(it)) }
        if (loggedIn) {
            library(doc.selectFirst(".book-action-panel library-button, library-button"))?.let { shelf(id, it) }
            like?.let { context.signals.set(id, Books.LIKED.key, Books.YES.key.takeIf { _ -> it.groupValues[2] != "null" }) }
            MARKS.find(html)?.groupValues?.get(1)?.let { marks ->
                val values = Regex("\"?([A-Za-z]+)\"?").findAll(marks).map { it.groupValues[1] }.filter { it != "null" }.toList()
                context.signals.set(id, AuthorToday.SIGNAL_REACTION, values.sorted().joinToString(",").ifEmpty { null })
            }
            HIDDEN.find(html)?.groupValues?.get(1)?.let { context.signals.set(id, AuthorToday.SIGNAL_HIDDEN, if (it == "true") "yes" else null) }
        }
        return id
    }

    // --- Reviews ---

    /**
     * The reviews of a work as its page of reviews lists them: excerpts of the long ones. A review
     * read whole on its own page before is not replaced by its excerpt.
     */
    private fun reviewList(doc: Document, workId: String): Boolean {
        if (items.find(workId) == null) return false
        val known = items.reviewContents(workId)
        val reviews = doc.select("article.post[id^=post_]").mapNotNull { article ->
            val reviewId = article.id().removePrefix("post_")
            val body = article.selectFirst(".rich-content") ?: return@mapNotNull null
            val text = PageText.of(body)
            val excerpt = article.selectFirst("a.btn[href=/review/$reviewId]") != null
            if (excerpt && (known[reviewId]?.length ?: 0) > text.length) return@mapNotNull null
            ReviewData(
                reviewId,
                article.selectFirst("time .mr a")?.text()?.trim(),
                null,
                text,
                article.selectFirst("time [data-time]")?.attr("data-time")?.let(::instant),
            )
        }
        if (reviews.isNotEmpty()) items.saveReviews(workId, reviews)
        return reviews.isNotEmpty()
    }

    /** A review read whole, on its own page. */
    private fun review(doc: Document, reviewId: String): String? {
        val article = doc.selectFirst("article.full-post") ?: return null
        val workId = article.selectFirst(".book-review-info .book-title a[href^=/work/]")?.attr("href")
            ?.let { WORK_ID.find(it)?.groupValues?.get(1) } ?: return null
        if (items.find(workId) == null) return null
        val body = article.select(".fr-view.rich-content").firstOrNull { it.closest(".book-review-info") == null } ?: return null
        items.saveReviews(
            workId,
            listOf(
                ReviewData(
                    reviewId,
                    article.selectFirst("header time .mr a")?.text()?.trim(),
                    null,
                    PageText.of(body),
                    article.selectFirst("header [data-time]")?.attr("data-time")?.let(::instant),
                ),
            ),
        )
        return workId
    }

    // --- The reader ---

    /** The text of a chapter as the reader shows it; nothing while the reader has not loaded it yet. */
    private fun reader(doc: Document, workId: String, chapterId: String, now: Instant): String? {
        val container = doc.selectFirst("#text-container") ?: return null
        val heading = container.selectFirst("h1")?.text()?.trim()
        val text = PageText.of(container, "h1, script, style")
        if (text.isBlank()) return null
        if (items.find(workId) == null) {
            // The reader before the work's page: the title is the page's, "Книга <title>, <chapter>, <author> читать онлайн"
            val title = doc.title().removePrefix("Книга ").substringBefore(", ${heading ?: "\u0000"}").substringBefore(" читать онлайн").trim()
            if (title.isEmpty()) return null
            items.upsert(ItemHead(workId, "$BASE/work/$workId", title), now)
        }
        items.saveParts(workId, listOf(PartData(chapterId, -1, heading, text)))
        return workId
    }

    companion object {
        private val WORK = Regex("/work/(\\d+)(/reviews)?/?")
        private val REVIEW = Regex("/review/(\\d+)/?")
        private val READER = Regex("/reader/(\\d+)/(\\d+)/?")
        private val WORK_ID = Regex("^/work/(\\d+)")
        private val AUTHOR_LOGIN = Regex("^/u/([^/?#]+)")

        /** The genre fan fiction is filed under. */
        private const val FANFICTION_GENRE = "fanfiction"
        private const val ALTERNATIVE_HISTORY_GENRE = "sf-history"
        private val LIBRARY_STATE = Regex("state:\\s*'([A-Za-z]+)'")
        private val LOGGED_IN = Regex("isAuthenticated:\\s*(true|false)")
        private val LIKE_BUTTON = Regex("name: 'like-button', params: \\{targetId: \\d+, likeCount: (\\d+), type: 'Work', disabled: \\w+, voteId: ([^}\\s]+)\\s*}")
        private val MARKS = Regex("\"workMarks\":(\\[[^\\]]*]|null)")
        private val HIDDEN = Regex("\"isDisliked\":(true|false)")

        /** The shelves of the user's library as the site names them, to the standard ones. */
        private val SHELVES = mapOf(
            "Reading" to Books.Shelf.READING, "Saved" to Books.Shelf.SAVED, "Finished" to Books.Shelf.FINISHED,
            "Disliked" to Books.Shelf.DISLIKED, "Purchased" to Books.Shelf.PURCHASED,
        )

        /** The statuses of the site by their text, to the standard ones. */
        private val STATUSES = mapOf("в процессе" to Books.Status.IN_PROGRESS, "весь текст" to Books.Status.FINISHED, "заморожен" to Books.Status.FROZEN)

        private fun instant(value: String): Instant? = runCatching { Instant.parse(value.let { if (it.endsWith("Z")) it else "${it}Z" }) }.getOrNull()
    }
}
