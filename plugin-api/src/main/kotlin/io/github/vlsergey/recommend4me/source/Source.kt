package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.i18n.Label
import java.time.Instant

/** How a source gets its items. */
enum class SourceMode {
    /** The whole catalogue, resumable: hours or days of requests to the site. */
    SCRAPE_ALL,

    /** What is new since the last time. */
    UPDATES,

    /**
     * The pages the user opens in the browser, sent by the extension: the application itself
     * requests nothing from the site.
     */
    BROWSER,

    /** One work's page downloaded by the application when the user asks for it ([Source.refresh]): no catalogue, no job. */
    PAGE,
}

/** The modes that are jobs over the catalogue, run in the background. */
val SourceMode.job: Boolean get() = this == SourceMode.SCRAPE_ALL || this == SourceMode.UPDATES

/** Whether the application downloads a work's page itself: by [SourceMode.PAGE], or by a job's own requests. */
val Source.downloadsPages: Boolean get() = modes.any { it == SourceMode.PAGE || it.job }

/**
 * A site the works come from. Implemented by a plugin and declared as a Spring bean.
 *
 * Everything a source learns it writes through the [SourceContext] it is handed: the generic
 * data of its items into the application's tables, anything else into tables of its own
 * ([migrations]) in the same source database.
 */
interface Source {
    /** Stable: the name of the source's folder, of its databases and the first half of every item key. */
    val id: String

    /** The name shown to the user: the site's own, as it is known in each language — "Книга Фанфиков", "Ficbook". */
    val title: Label

    /** The content type the source's works belong to: `games`, `books`. */
    val contentType: String

    val homepage: String

    val schema: SourceSchema

    val modes: Set<SourceMode>

    /**
     * Flyway locations of the source's own tables in its source database, e.g.
     * `classpath:db/f95zone`. They are migrated with a history table of their own.
     */
    val migrations: List<String> get() = emptyList()

    val settings: List<SettingDef> get() = emptyList()

    /** The page of the item on the site. */
    fun itemUrl(itemId: String): String

    /**
     * The item a page of the site is about — a work's page, a chapter of it, its reviews — or null
     * when the address is not one item's. The extension shows the item's panel on such a page, and
     * a card linking to it gets the item's prediction.
     */
    fun itemIdOf(url: String): String? = null

    /** The user's own actions on the site the source reads ([SiteSignals]), with the names to show. */
    val signals: List<SignalDef> get() = emptyList()

    /** How the extension marks the site's pages up; null — it does not. */
    val pageDecor: PageDecor? get() = null

    // --- Browser tracking ---

    /** The addresses of the pages the source wants from the browser, matched against the whole URL. */
    val capturePatterns: List<Regex> get() = emptyList()

    /**
     * Raised when the source reads its pages differently: every kept page ([SourceContext.keepPage])
     * is handed to [capture] again on the next start.
     */
    val parserVersion: Int get() = 1

    /**
     * Reads a page the browser sent — or one kept before, read again after the parser changed —
     * and writes what it says. Returns the items it touched.
     */
    fun capture(page: CapturedPage, context: SourceContext): List<String> = emptyList()

    // --- Scraping ---

    /** Runs a job of [mode] (not [SourceMode.BROWSER]) to its end, cancelled or failed. */
    fun scrape(mode: SourceMode, context: SourceContext, job: Job): Unit =
        throw UnsupportedOperationException("$id does not scrape")

    /**
     * Downloads the item's page again now — the button of a single item, or a work the user has
     * just graded whose details are missing. Returns false when the source cannot.
     */
    fun refresh(itemId: String, context: SourceContext): Boolean = false

    /** Whether [refresh] would bring something the item lacks: the details of its current version. */
    fun needsRefresh(itemId: String, context: SourceContext): Boolean = false

    /**
     * How far the whole catalogue is loaded ([SourceMode.SCRAPE_ALL]): what it is doing and how much
     * of it is done, across restarts; null — the source does not tell.
     */
    fun scrapeProgress(context: SourceContext): ScrapeProgress? = null

    // --- Background work of its own ---

    /**
     * Called once the application is up: a source may start background work of its own here (the
     * reviews of f95zone read without end), on threads of its own, until [stop].
     */
    fun start(context: SourceContext) {}

    /** Called when the application stops. */
    fun stop() {}

    // --- Pictures ---

    /**
     * Where a picture of an item is downloaded from, the best first: an original before the
     * preview it was listed by. The application tries them in order.
     */
    fun pictureAddresses(url: String): List<String> = listOf(url)

    /** Headers of a picture download: a referer, a user agent. */
    fun pictureHeaders(url: String, context: SourceContext): Map<String, String> = emptyMap()
}

/**
 * How far a scrape of the whole catalogue has got: [stage] — what it does now or would do next
 * ("feed", "details"); [done] of [total] in it; [complete] — the catalogue is loaded and only
 * updates are left.
 */
class ScrapeProgress(val stage: String, val done: Int, val total: Int, val complete: Boolean)

/** A page the browser sent: its address, the DOM as the user saw it, and when. */
class CapturedPage(
    val url: String,
    val html: String,
    val capturedAt: Instant,
)
