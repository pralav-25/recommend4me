package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
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
import io.github.vlsergey.recommend4me.source.SignalDef
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.source.TextRole

/**
 * author.today — books, read in the browser and seen by the extension only: the application
 * requests no page of the site. What a page shows is kept: the cards of every list, a work's page
 * with its table of contents, its reviews, the text of every chapter read; and the user's own marks
 * on the work — the shelf of the library, the like, the reactions, "not interested".
 */
class AuthorToday : Source {
    override val id = ID
    override val contentType = "books"
    override val homepage = BASE
    override val modes = setOf(SourceMode.BROWSER)

    override val schema = SourceSchema(
        facets = listOf(
            Books.AUTHOR.asFacet(AUTHOR),
            FacetDef(GENRE, filter = true, onCard = true, searchWeight = 0.8f, role = FacetRole.CONTENT),
            FacetDef(FORM, filter = true, role = FacetRole.FORM),
            Books.TAG.asFacet(TAG),
            Books.STATUS.asFacet(STATUS),
            FacetDef(SERIES, searchWeight = 0.7f, role = FacetRole.SERIES),
        ),
        numbers = listOf(
            // Characters, not words: a number of its own
            NumberDef(CHARS, NumberScale.LOG, sortable = true, role = NumberRole.SIZE),
            StandardNumbers.VIEWS.asNumber(VIEWS),
            StandardNumbers.LIKES.asNumber(LIKES),
            NumberDef(COMMENTS, NumberScale.LOG),
            NumberDef(REVIEWS, NumberScale.LOG),
            NumberDef(AWARDS, NumberScale.LOG),
        ),
        texts = listOf(
            TextDef(ANNOTATION, block = "text:annotation", searchWeight = 0.6f, role = TextRole.DESCRIPTION),
            TextDef(NOTES, block = "text:notes", searchWeight = 0.5f, role = TextRole.NOTES),
        ),
        reviews = true,
        parts = true,
        // A work of the genre "Фанфик" is fan fiction, of "Альтернативная история" our world changed
        kindFrom = GENRE,
    )

    override val capturePatterns = listOf(
        Regex("https://author\\.today/(work|reader|review|search|collection|collections|top|u/[^/]+/(works|library|reviews))([/?#].*)?"),
    )

    /**
     * 2: whether a work is fan fiction, by its genres. 3: the standard statuses; the shelf of the
     * finished as the standard "read". 4: the shelf as the standard one. 5: the pages that failed
     * when several pages of one work were read at once read again. 6: the alternative history as a
     * kind of its own, the erotic fan fiction as fan fiction.
     */
    override val parserVersion = 6

    override fun itemUrl(itemId: String) = "$BASE/work/$itemId"

    override fun itemIdOf(url: String): String? = ITEM_ADDRESS.find(url)?.groupValues?.get(1)

    override val signals = listOf(
        Books.SHELF.asSignal(),
        Books.READ.asSignal(),
        Books.LIKED.asSignal(),
        SignalDef(
            SIGNAL_REACTION,
            setOf(
                "Perfect", "Waiting", "ImpossibleToPutDown", "Boring", "Funny", "Sadly",
                "Cute", "Wisely", "Facepalm", "DoNotRecommend", "Obscene", "Unclear",
            ),
        ),
        SignalDef(SIGNAL_HIDDEN, setOf(Books.YES.key)),
    )

    override val pageDecor = PageDecor(
        // After the whole card of the book: within it the columns are laid out by hand
        panelAfter = ".book-panel",
        // The site's tags and genres are its word, never answered on: the work's tags after them
        facets = listOf(FacetDecor(Books.TAGS.key, after = ".book-meta-panel .tags")),
        reviews = ReviewDecor("article.post[id^='post_']", idPrefix = "post_"),
        pictures = PictureDecor(".book-cover img.cover-image"),
        cards = CardDecor(".book-row", ".book-title a[href^='/work/']"),
    )

    override fun capture(page: CapturedPage, context: SourceContext): List<String> = AuthorTodayPages(context).read(page)

    companion object {
        const val ID = "author.today"
        const val BASE = "https://author.today"

        /** A work's page, its reviews, a chapter in the reader. */
        private val ITEM_ADDRESS = Regex("^https?://(?:www\\.)?author\\.today/(?:work|reader)/(\\d+)(?:[/?#]|$)")

        const val AUTHOR = "author"
        const val GENRE = "genre"
        const val FORM = "form"
        const val TAG = "tag"
        const val STATUS = "status"
        const val SERIES = "series"

        const val CHARS = "chars"
        const val VIEWS = "views"
        const val LIKES = "likes"
        const val COMMENTS = "comments"
        const val REVIEWS = "reviews"
        const val AWARDS = "awards"

        const val ANNOTATION = "annotation"
        const val NOTES = "notes"

        /** The user's own: the shelf of the library the work is on. */
        const val SIGNAL_REACTION = "reaction"
        const val SIGNAL_HIDDEN = "hidden"
    }
}
