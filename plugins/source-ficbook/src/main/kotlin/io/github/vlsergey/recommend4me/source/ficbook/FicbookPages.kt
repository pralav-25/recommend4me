package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.BASE
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.COMMENTS
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.DEDICATION
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.DIRECTION
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.LIKES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.NOTES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.ONLY_PART
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PAGES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PARTS
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.RATING
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.SERIES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.STATUS
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.TAG
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.WORDS
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant

/**
 * Reads the pages of ficbook.net and writes what they say: the cards of a list (a fandom, a tag, a
 * search, a collection), a work's header and table of contents, the text of a part, the comments.
 */
class FicbookPages(private val context: SourceContext) {

    private val items = context.items

    fun read(page: CapturedPage): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        val path = runCatching { URI(page.url).path }.getOrDefault("")
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt)
        READFIC.matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            val rest = m.groupValues[2]
            when {
                rest.isEmpty() -> work(doc, id, page.capturedAt)?.let { touched += it }
                rest == "comments" -> Unit
                rest.all { it.isDigit() } -> part(doc, id, rest, page.capturedAt)?.let { touched += it }
            }
            if (comments(doc, id)) touched += id
        }
        return touched.toList()
    }

    // --- Cards of a list ---

    private fun cards(doc: Document, now: Instant): List<String> = doc.select("article.fanfic-inline").mapNotNull { card ->
        val link = card.selectFirst(".fanfic-inline-title a[href^=/readfic/]") ?: return@mapNotNull null
        val id = workId(link.attr("href")) ?: return@mapNotNull null
        val title = link.text().trim().ifEmpty { return@mapNotNull null }
        val info = card.select("dl.fanfic-inline-info").associate { dl ->
            dl.selectFirst("dt")?.text()?.trim()?.removeSuffix(":").orEmpty() to dl.selectFirst("dd")
        }
        val updated = info.entries.firstOrNull { it.key.startsWith("Дата") }?.value?.text()?.let { PageText.russianDate(it) }
        items.upsert(ItemHead(id, "$BASE/readfic/$id", title, updatedAt = updated), now)
        badges(id, card.selectFirst(".fanfic-badges"))
        card.selectFirst(".js-like-count")?.text()?.let(PageText::number)?.let { items.setNumbers(id, mapOf(LIKES to it.toDouble())) }
        described(id, info.mapValues { it.value })
        card.select(".tags a.tag").takeIf { it.isNotEmpty() }?.let { tags(id, it) }
        card.selectFirst(".fanfic-description-text")?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
        card.selectFirst("img.fic-cover")?.attr("src")?.takeIf { it.isNotBlank() }?.let { items.setPictures(id, listOf(it)) }
        id
    }

    /** The direction, the rating and the status a card or a header shows as labels. */
    private fun badges(id: String, badges: Element?) {
        badges ?: return
        badges.selectFirst(".direction")?.let { d ->
            val key = d.classNames().firstOrNull { it.startsWith("direction-") }?.removePrefix("direction-")
            val name = d.text().trim().ifEmpty { d.attr("title").substringBefore(" —").trim() }
            if (key != null) items.setFacet(id, DIRECTION, listOf(FacetValue(key, name.ifEmpty { key })))
        }
        badges.select("[class*=ds-label-rating-]").firstOrNull()?.let { r ->
            val key = r.classNames().first { it.startsWith("ds-label-rating-") }.removePrefix("ds-label-rating-")
            items.setFacet(id, RATING, listOf(FacetValue(key, r.text().trim().ifEmpty { key })))
        }
        badges.select("[class*=ds-label-status-]").firstOrNull()?.let { s ->
            val key = s.classNames().first { it.startsWith("ds-label-status-") }.removePrefix("ds-label-status-")
            val text = s.text().trim()
            // The standard status by the label's class or its text; another as the site writes it, for the application to tell of
            val status = STATUSES[key] ?: STATUSES[text.lowercase()]
            items.setFacet(id, STATUS, listOf(status?.let { FacetValue(it.key, it.label.ru) } ?: FacetValue(key, text.ifEmpty { key })))
        }
    }

    /** What the labelled lines of a card or a header say: the author, the fandoms, the series, the pairings, the size. */
    private fun described(id: String, info: Map<String, Element?>) {
        info["Автор"]?.let { dd ->
            val authors = dd.select("a[href^=/authors/]").mapNotNull { a ->
                AUTHOR_ID.find(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, a.text().trim()) }
            }
            if (authors.isNotEmpty()) items.setFacet(id, AUTHOR, authors)
        }
        info["Фэндом"]?.let { dd ->
            val fandoms = dd.select("a[href^=/fanfiction/]").map { a ->
                FacetValue(a.attr("href").removePrefix("/fanfiction/").substringBefore('?'), a.text().trim())
            }
            if (fandoms.isNotEmpty()) {
                items.setFacet(id, FANDOM, fandoms)
                // A work of the fandom "originals" is an original one; of any other, fan fiction
                val original = fandoms.all { it.key == ORIGINALS }
                items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(if (original) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION)))
            }
        }
        info["Серия"]?.selectFirst("a[href^=/series/]")?.let { a ->
            items.setFacet(id, SERIES, listOf(FacetValue(a.attr("href").removePrefix("/series/").substringBefore('?'), a.text().trim())))
        }
        info["Пэйринг и персонажи"]?.let { dd ->
            // The line as the site shows it: what the application works the pairings and characters out from
            items.setTexts(id, mapOf(PAIRINGS_LINE to PageText.of(dd)))
            // Every entry of the line as written, one field: a pairing "A/B" and a character alone
            val entries = dd.select("a").map { it.text().trim() }.flatMap { it.split(", ") }.map { it.trim() }.filter { it.isNotEmpty() }
            items.setFacet(id, PAIRING, entries.map(::entry).distinctBy { it.key })
        }
        info["Размер"]?.text()?.let { size ->
            items.setNumbers(
                id,
                listOfNotNull(
                    PageText.numberBefore(size, "стр")?.let { PAGES to it.toDouble() },
                    PageText.numberBefore(size, "слов")?.let { WORDS to it.toDouble() },
                    PageText.numberBefore(size, "част")?.let { PARTS to it.toDouble() },
                ).toMap(),
            )
        }
    }

    private fun tags(id: String, links: List<Element>) =
        items.setFacet(id, TAG, links.map { a -> a.text().trim().let { FacetValue(it.lowercase(), it) } }.filter { it.key.isNotEmpty() })

    // --- A work's page ---

    private fun work(doc: Document, id: String, now: Instant): String? {
        val title = doc.selectFirst("h1.heading, h1[itemprop=name]")?.text()?.trim()?.ifEmpty { null } ?: return null
        val hat = doc.selectFirst(".fanfic-hat")
        val blocks = hat?.select(".description > .mb-10").orEmpty().associate { block ->
            block.selectFirst("strong")?.text()?.trim()?.removeSuffix(":").orEmpty() to block.selectFirst("strong + div")
        }
        val parts = doc.select("ul.list-of-fanfic-parts li.part").mapIndexedNotNull { i, li ->
            val a = li.selectFirst("a.part-link[href^=/readfic/]") ?: return@mapIndexedNotNull null
            val partId = READFIC.matchEntire(a.attr("href").substringBefore('#').substringBefore('?'))?.groupValues?.get(2)
                ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapIndexedNotNull null
            PartData(partId, i, li.selectFirst(".part-title")?.text()?.trim(), null, li.selectFirst(".part-info [title]")?.attr("title")?.let { PageText.russianDate(it) })
        }
        items.upsert(ItemHead(id, "$BASE/readfic/$id", title, updatedAt = parts.mapNotNull { it.publishedAt }.maxOrNull()), now)
        badges(id, doc.selectFirst("section.fanfic-badges"))
        doc.selectFirst("section.fanfic-badges button span")?.text()?.let(PageText::number)?.let { items.setNumbers(id, mapOf(LIKES to it.toDouble())) }
        val authors = hat?.select("[itemprop=author]").orEmpty().mapNotNull { a ->
            AUTHOR_ID.find(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, a.text().trim()) }
        }
        if (authors.isNotEmpty()) items.setFacet(id, AUTHOR, authors)
        described(id, blocks.filterKeys { it != "Автор" })
        blocks["Метки"]?.select("a.tag")?.let { tags(id, it) }
        items.setTexts(
            id,
            mapOf(
                ANNOTATION to blocks["Описание"]?.let(PageText::of),
                NOTES to blocks["Примечания"]?.let(PageText::of),
                DEDICATION to blocks["Посвящение"]?.let(PageText::of),
            ),
        )
        doc.select("a[href=/readfic/$id/comments#comments-list]").firstNotNullOfOrNull { PageText.number(it.text()) }
            ?.let { items.setNumbers(id, mapOf(COMMENTS to it.toDouble())) }
        marks(doc, id)
        if (parts.isNotEmpty()) items.saveParts(id, parts)
        // A work of one part has its text on its own page, without a table of contents
        else doc.selectFirst("#content")?.let { content ->
            val text = PageText.of(content, NOT_THE_TEXT)
            if (text.isNotBlank()) items.saveParts(id, listOf(PartData(ONLY_PART, 0, title, text)))
        }
        return id
    }

    /**
     * The user's own marks of the work in the buttons of its header — liked, read, followed: a
     * button pressed is coloured "success". Read only of a page of a user logged in, the one that
     * has the menu of their liked works: to anyone else the page tells nothing of them.
     */
    private fun marks(doc: Document, id: String) {
        if (doc.selectFirst("a[href=/home/liked_fanfics]") == null) return
        fun pressed(icon: String): Boolean? =
            doc.select(".hat-actions-container button:has(svg.$icon)").firstOrNull()?.let { b -> b.classNames().any { it.startsWith("ds-btn-success") } }
        pressed("ic_thumbs-up")?.let { context.signals.set(id, Books.LIKED.key, Books.YES.key.takeIf { _ -> it }) }
        pressed("ic_star-empty")?.let { context.signals.set(id, Ficbook.SIGNAL_FOLLOWED, Books.YES.key.takeIf { _ -> it }) }
        // The box "read" of the work, ticked or not
        when {
            doc.selectFirst("button:has(svg.ic_checkbox-checked2)") != null -> context.signals.set(id, Books.READ.key, Books.YES.key)
            doc.selectFirst("button:has(svg.ic_checkbox-unchecked2)") != null -> context.signals.set(id, Books.READ.key, null)
        }
    }

    // --- A part ---

    private fun part(doc: Document, id: String, partId: String, now: Instant): String? {
        val content = doc.selectFirst("#content") ?: return null
        val text = PageText.of(content, NOT_THE_TEXT)
        if (text.isBlank()) return null
        if (items.find(id) == null) {
            val title = doc.selectFirst("h1.heading, h1[itemprop=name]")?.text()?.trim()?.ifEmpty { null } ?: return null
            items.upsert(ItemHead(id, "$BASE/readfic/$id", title), now)
        }
        marks(doc, id)
        val area = doc.selectFirst(".title-area")
        items.saveParts(
            id,
            listOf(
                PartData(
                    partId, -1, area?.selectFirst("h2")?.text()?.trim(), text,
                    area?.selectFirst(".part-date [title]")?.attr("title")?.let { PageText.russianDate(it) },
                ),
            ),
        )
        return id
    }

    // --- Comments ---

    /** The comments the page shows, on the work's own page of comments or under a part; quotes of other comments left out. */
    private fun comments(doc: Document, id: String): Boolean {
        val list = doc.select("article.comment-container[id^=com]")
        if (list.isEmpty() || items.find(id) == null) return false
        items.saveReviews(id, list.mapNotNull { c ->
            val message = c.selectFirst(".js-comment-message") ?: return@mapNotNull null
            val text = PageText.of(message, ".quoted, script, style").ifBlank { return@mapNotNull null }
            ReviewData(
                c.id().removePrefix("com"),
                c.selectFirst(".js-comment-author")?.text()?.trim(),
                null,
                text,
                c.selectFirst("time.comment-date")?.let { PageText.russianDate(it.attr("datetime").ifBlank { it.text() }) },
            )
        })
        return true
    }

    companion object {
        /** A work's address: "/readfic/<id>", "/readfic/<id>/<part>", "/readfic/<id>/comments". */
        private val READFIC = Regex("/readfic/([0-9a-f-]+)/?([^/]*)/?")
        private val AUTHOR_ID = Regex("^/authors/([^/?#]+)")

        /** The fandom of the original works. */
        private const val ORIGINALS = "no_fandom/originals"

        /** The statuses of the site, by the class of their label and by its text, to the standard ones. */
        private val STATUSES = mapOf(
            "in-progress" to Books.Status.IN_PROGRESS, "в процессе" to Books.Status.IN_PROGRESS,
            "finished" to Books.Status.FINISHED, "завершён" to Books.Status.FINISHED, "завершен" to Books.Status.FINISHED,
            "frozen" to Books.Status.FROZEN, "заморожен" to Books.Status.FROZEN,
        )

        /**
         * What the site puts into the text of a part that is not the text: the promotion of another
         * work, put by its scripts into a placeholder among the paragraphs; and the scripts and styles.
         */
        private const val NOT_THE_TEXT = ".js-fanfic-text-promo-placeholder, [class*=promo], script, style"

        /**
         * An entry of the line as written — a pairing "A/B", or a character alone: a pairing is one
         * whatever the order of its names — the key has them sorted.
         */
        private fun entry(written: String): FacetValue =
            FacetValue(written.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString("/"), written)

        private fun workId(href: String): String? = READFIC.matchEntire(href.substringBefore('?').substringBefore('#'))?.groupValues?.get(1)
    }
}
