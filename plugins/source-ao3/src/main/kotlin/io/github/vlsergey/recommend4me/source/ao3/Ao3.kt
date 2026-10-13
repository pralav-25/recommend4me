package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.CardDecor
import io.github.vlsergey.recommend4me.source.FacetDecor
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.ReviewDecor
import io.github.vlsergey.recommend4me.source.SignalDef
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * archiveofourown.org — the Archive of Our Own: fan works in every language. Read in the browser
 * and seen by the extension — the cards of every list, a work's header, its chapters, the comments
 * shown — and a work's whole page downloaded by the application itself when the user asks for it
 * (or grades a work known only by its card): one request, never a crawl of the catalogue.
 *
 * The site's tags are its word: the fandoms, the relationships and the characters wrangled by its
 * volunteers, which the application works the universes, characters and pairings out from.
 */
class Ao3 : Source {
    override val id = ID
    override val title = Label.same("Archive of Our Own")
    override val contentType = "books"
    override val homepage = BASE
    override val modes = setOf(SourceMode.BROWSER, SourceMode.PAGE)

    override val schema = SourceSchema(
        facets = listOf(
            Books.AUTHOR.asFacet(AUTHOR),
            Books.FANDOM.asFacet(FANDOM),
            FacetDef(RATING, Label("Rating", "Рейтинг", "Rejting"), filter = true, onCard = true, role = FacetRole.CONTENT),
            FacetDef(WARNING, Label("Warnings", "Предупреждения", "Upozorenja"), filter = true, role = FacetRole.CONTENT),
            FacetDef(CATEGORY, Label("Category", "Категория", "Kategorija"), filter = true, onCard = true, role = FacetRole.CONTENT),
            Books.STATUS.asFacet(STATUS),
            FacetDef(LANGUAGE, Label("Language", "Язык", "Jezik"), filter = true, onCard = true),
            Books.TAG.asFacet(TAG),
            // The relationships and the characters as the site's tags name them, one field: the
            // application works the characters and pairings of the universes out of them
            Books.PAIRINGS.asFacet(PAIRING),
            FacetDef(SERIES, Label("Series", "Серия", "Serijal"), searchWeight = 0.7f, role = FacetRole.SERIES),
        ),
        numbers = listOf(
            NumberDef(WORDS, Label("Words", "Слов", "Reči"), NumberScale.LOG, sortable = true, role = NumberRole.SIZE),
            Books.PARTS.asNumber(PARTS),
            // Kudos are the site's likes: one number with every site's
            StandardNumbers.LIKES.asNumber(KUDOS),
            NumberDef(BOOKMARKS, Label("Bookmarks", "Закладки", "Obeleživači"), NumberScale.LOG),
            NumberDef(HITS, Label("Hits", "Просмотры", "Pregledi"), NumberScale.LOG, sortable = true, role = NumberRole.REACH),
            NumberDef(COMMENTS, Label("Comments", "Комментарии", "Komentari"), NumberScale.LOG),
        ),
        texts = listOf(
            TextDef(ANNOTATION, Label("Summary", "Описание", "Opis"), block = "text:annotation", searchWeight = 0.6f, role = TextRole.DESCRIPTION),
            TextDef(NOTES, Label("Notes", "Примечания", "Beleške"), block = "text:notes", searchWeight = 0.5f, role = TextRole.NOTES),
            TextDef(
                PAIRINGS_LINE, Label("Relationships and characters on the site", "Отношения и персонажи на сайте", "Veze i likovi na sajtu"),
                searchWeight = 0.7f, searchByMeaning = true,
            ),
        ),
        reviewsLabel = Label("Comments", "Комментарии", "Komentari"),
        partsLabel = Label("Chapters", "Главы", "Poglavlja"),
        universeLine = PAIRINGS_LINE,
        // A work of the fandom "Original Work" is an original one
        kindFrom = FANDOM,
    )

    override val capturePatterns = listOf(
        Regex("https://archiveofourown\\.org/(works|tags|users|series|collections|bookmarks)([/?#].*)?"),
    )

    override val signals = listOf(
        // Kudos is the site's "I liked it"
        Books.LIKED.asSignal(),
        SignalDef(SIGNAL_BOOKMARKED, Label("Bookmarked", "В закладках", "U obeleživačima"), mapOf(Books.YES.key to Books.YES.label)),
        SignalDef(
            SIGNAL_SUBSCRIBED, Label("Subscribed to updates", "Подписка на обновления", "Pretplata na ažuriranja"),
            mapOf(Books.YES.key to Books.YES.label),
        ),
        SignalDef(SIGNAL_LATER, Label("Marked for later", "Отложено на потом", "Ostavljeno za kasnije"), mapOf(Books.YES.key to Books.YES.label)),
    )

    override fun itemUrl(itemId: String) = "$BASE/works/$itemId"

    override fun itemIdOf(url: String): String? = ITEM_ADDRESS.find(url)?.groupValues?.get(1)

    override val pageDecor = PageDecor(
        panelAfter = "dl.work.meta",
        // The language closes the site's tags on every work: the work's tags and the worked out
        // universes, characters and pairings go in lines of their own after it
        facets = listOf(
            FacetDecor(Books.TAGS.key, after = AFTER_TAGS),
            FacetDecor(UniverseFacets.UNIVERSE, after = AFTER_TAGS),
            FacetDecor(UniverseFacets.CHARACTERS, after = AFTER_TAGS),
            FacetDecor(UniverseFacets.SECONDARY_CHARACTERS, after = AFTER_TAGS),
            FacetDecor(UniverseFacets.PAIRINGS, after = AFTER_TAGS),
        ),
        reviews = ReviewDecor("li.comment[id^='comment_']", idPrefix = "comment_"),
        // A work's card in every list — its works, a search, the bookmarks, a series, the history
        cards = CardDecor("li.blurb", "h4.heading a[href^='/works/']"),
    )

    override fun capture(page: CapturedPage, context: SourceContext): List<String> = Ao3Pages(context).read(page)

    /** The work's whole page — every chapter, the adult works' warning passed — read as if the browser had sent it. */
    override fun refresh(itemId: String, context: SourceContext): Boolean {
        val url = "$BASE/works/$itemId?view_adult=true&view_full_work=true"
        val response = paced {
            http.send(
                HttpRequest.newBuilder(URI(url)).timeout(TIMEOUT).header("User-Agent", USER_AGENT).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        when (response.statusCode()) {
            200 -> Unit
            404 -> return false
            429 -> error("the site asks to wait before the next request (429)")
            else -> error("the site answered ${response.statusCode()}")
        }
        return itemId in capture(CapturedPage(url, response.body(), Instant.now()), context)
    }

    /** When the last request to the site was sent: the next waits out [PAUSE] after it. */
    private var lastRequest: Instant = Instant.EPOCH

    /**
     * One request at a time, [PAUSE] apart — the site's own asking of anything automated (its post
     * on the data dump, 2021: a delay between requests, "bot" in the user agent).
     */
    @Synchronized
    private fun <T> paced(request: () -> T): T {
        val wait = Duration.between(Instant.now(), lastRequest.plus(PAUSE))
        if (!wait.isNegative) Thread.sleep(wait.toMillis())
        try {
            return request()
        } finally {
            lastRequest = Instant.now()
        }
    }

    /** A work known by its card alone — no chapter of it — is downloaded once it is graded. */
    override fun needsRefresh(itemId: String, context: SourceContext): Boolean =
        context.items.find(itemId) != null && context.items.parts(itemId).isEmpty()

    companion object {
        const val ID = "ao3"
        const val BASE = "https://archiveofourown.org"

        /** A work's page and its chapters. */
        private val ITEM_ADDRESS = Regex("^https?://(?:www\\.)?archiveofourown\\.org/works/(\\d+)(?:[/?#]|$)")

        private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
        private val TIMEOUT: Duration = Duration.ofSeconds(60)

        /** Says what asks, as the site asks a bot to: one page of a work at its reader's request. */
        private const val USER_AGENT = "recommend4me-bot (a personal recommender; one work's page at its user's request)"

        private val PAUSE: Duration = Duration.ofSeconds(5)

        private const val AFTER_TAGS = "dl.work.meta dd.language"

        const val AUTHOR = "author"
        const val FANDOM = "fandom"
        const val RATING = "rating"
        const val WARNING = "warning"
        const val CATEGORY = "category"
        const val STATUS = "status"
        const val LANGUAGE = "language"
        const val TAG = "tag"
        const val PAIRING = "pairing"
        const val SERIES = "series"

        const val WORDS = "words"
        const val PARTS = "parts"
        const val KUDOS = "kudos"
        const val BOOKMARKS = "bookmarks"
        const val HITS = "hits"
        const val COMMENTS = "comments"

        const val ANNOTATION = "annotation"
        const val NOTES = "notes"
        const val PAIRINGS_LINE = "pairingsLine"

        const val SIGNAL_BOOKMARKED = "bookmarked"
        const val SIGNAL_SUBSCRIBED = "subscribed"
        const val SIGNAL_LATER = "later"

        /** The part of a work of one chapter, whose page names no chapter. */
        const val ONLY_PART = "text"
    }
}
