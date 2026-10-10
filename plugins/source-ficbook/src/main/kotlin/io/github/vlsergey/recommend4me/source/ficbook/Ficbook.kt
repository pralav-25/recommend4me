package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.CardDecor
import io.github.vlsergey.recommend4me.source.FacetDecor
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.PictureDecor
import io.github.vlsergey.recommend4me.source.ReviewDecor
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SignalDef
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.universe.UniverseFacets

/**
 * ficbook.net — fanfics and originals, read in the browser and seen by the extension only: the
 * site checks its visitors, and the application requests no page of it. What a page shows is kept:
 * the cards of every list, a work's header with its table of contents, the text of every part read,
 * and the readers' comments ("Отзывы") as the opinions of other people about the work.
 */
class Ficbook : Source {
    override val id = ID
    override val title = "Книга Фанфиков"
    override val contentType = "books"
    override val homepage = BASE
    override val modes = setOf(SourceMode.BROWSER)

    override val schema = SourceSchema(
        facets = listOf(
            Books.AUTHOR.asFacet(AUTHOR),
            Books.FANDOM.asFacet(FANDOM),
            FacetDef(DIRECTION, "Направленность", filter = true, onCard = true, role = FacetRole.CONTENT),
            FacetDef(RATING, "Рейтинг", filter = true, onCard = true, role = FacetRole.CONTENT),
            Books.STATUS.asFacet(STATUS),
            Books.TAG.asFacet(TAG),
            // The pairings and characters as the authors write them: the site's strings, kept as they
            // are; the application works the characters and pairings of the universes out of them
            Books.PAIRINGS.asFacet(PAIRING),
            FacetDef(SERIES, "Серия", searchWeight = 0.7f, role = FacetRole.SERIES),
        ),
        numbers = listOf(
            NumberDef(WORDS, "Слов", NumberScale.LOG, sortable = true, role = NumberRole.SIZE),
            NumberDef(PAGES, "Страниц", NumberScale.LOG),
            NumberDef(PARTS, "Частей", NumberScale.LOG, filterable = true),
            NumberDef(LIKES, "Нравится", NumberScale.LOG, sortable = true, role = NumberRole.APPROVAL),
            NumberDef(COMMENTS, "Отзывы", NumberScale.LOG),
        ),
        texts = listOf(
            TextDef(ANNOTATION, "Описание", block = "text:annotation", searchWeight = 0.6f, role = TextRole.DESCRIPTION),
            TextDef(NOTES, "Примечания", block = "text:notes", searchWeight = 0.5f, role = TextRole.NOTES),
            TextDef(DEDICATION, "Посвящение", searchWeight = 0.3f, role = TextRole.NOTES),
            TextDef(PAIRINGS_LINE, "Пэйринг и персонажи на сайте", searchWeight = 0.7f, searchByMeaning = true),
        ),
        reviewsLabel = "Отзывы",
        partsLabel = "Части",
        universeLine = PAIRINGS_LINE,
        // A work of the fandom "Ориджиналы" is an original one
        kindFrom = FANDOM,
    )

    override val capturePatterns = listOf(
        Regex("https://ficbook\\.net/(readfic|fanfiction|tags|authors|collections|series|pairings|find|find-fanfics[^/?#]*|popular-fanfics[^/?#]*|home/[^?#]*)([/?#].*)?"),
    )

    /**
     * 2: the line of pairings and characters kept as the site writes it; a pairing is one whatever
     * the order of its names. 3: whether a work is fan fiction, by its fandom. 4: the promotion of
     * another work the site puts among the paragraphs of a part is not its text. 5: the standard
     * statuses; the user's marks of a work — liked, read, followed. 6: the pairings and the
     * characters alone in one field, every entry of the line as written; the fandom a standard one.
     */
    override val parserVersion = 6

    override val signals = listOf(
        Books.READ.asSignal(),
        Books.LIKED.asSignal(),
        SignalDef(SIGNAL_FOLLOWED, "Слежу за обновлениями", mapOf(Books.YES.key to Books.YES.label)),
    )

    override fun itemUrl(itemId: String) = "$BASE/readfic/$itemId"

    override fun itemIdOf(url: String): String? = ITEM_ADDRESS.find(url)?.groupValues?.get(1)

    override val pageDecor = PageDecor(
        panelAfter = "section.fanfic-hat",
        facets = listOf(
            // The site's tags are its word, never answered on: the work's tags after them
            FacetDecor(Books.TAGS.key, after = "section.fanfic-hat .tags"),
            // The universes, characters and pairings worked out go in lines of their own under the site's block of them
            FacetDecor(UniverseFacets.UNIVERSE, after = PAIRINGS_BLOCK),
            FacetDecor(UniverseFacets.CHARACTERS, after = PAIRINGS_BLOCK),
            FacetDecor(UniverseFacets.SECONDARY_CHARACTERS, after = PAIRINGS_BLOCK),
            FacetDecor(UniverseFacets.PAIRINGS, after = PAIRINGS_BLOCK),
        ),
        reviews = ReviewDecor("article.comment-container[id^='com']", idPrefix = "com"),
        pictures = PictureDecor("img.fic-cover"),
        cards = CardDecor("article.fanfic-inline", ".fanfic-inline-title a[href^='/readfic/']"),
    )

    override fun capture(page: CapturedPage, context: SourceContext): List<String> = FicbookPages(context).read(page)

    companion object {
        const val ID = "ficbook"
        const val BASE = "https://ficbook.net"

        /** A work's page, a part of it, its comments. */
        private val ITEM_ADDRESS = Regex("^https?://(?:www\\.)?ficbook\\.net/readfic/([0-9a-f-]+)(?:[/?#]|$)")

        const val AUTHOR = "author"
        const val FANDOM = "fandom"
        const val DIRECTION = "direction"
        const val RATING = "rating"
        const val STATUS = "status"
        const val SIGNAL_FOLLOWED = "followed"
        const val TAG = "tag"
        const val PAIRING = "pairing"
        const val SERIES = "series"

        const val WORDS = "words"
        const val PAGES = "pages"
        const val PARTS = "parts"
        const val LIKES = "likes"
        const val COMMENTS = "comments"

        const val ANNOTATION = "annotation"
        const val NOTES = "notes"
        const val DEDICATION = "dedication"
        const val PAIRINGS_LINE = "pairingsLine"

        /** The block of a work's header with its pairings and characters. */
        private const val PAIRINGS_BLOCK = "section.fanfic-hat .description > .mb-10:has(a.pairing-link)"

        /** The part of a work of one part, whose text is on the work's own page. */
        const val ONLY_PART = "text"
    }
}
