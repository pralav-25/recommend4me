package io.github.vlsergey.recommend4me.source

import org.jooq.DSLContext
import java.nio.file.Path
import java.time.Instant

/** What the application hands a source to work with. */
interface SourceContext {
    val sourceId: String

    /** The generic data of the source's items. */
    val items: ItemStore

    /** The source database, for the source's own tables. */
    val database: DSLContext

    val settings: SourceSettings

    /** The user's own actions seen on the site's pages. */
    val signals: SiteSignals

    /** The source's folder in the data folder, for whatever files it keeps. */
    val folder: Path

    /**
     * Keeps a page — a captured one, or one the source downloaded — so it can be read again when
     * the parser changes ([Source.parserVersion]); a page kept again under the same address replaces it.
     */
    fun keepPage(url: String, html: String, itemId: String?)

    /** The items the user has graded, any version: a scrape reads their pages first. */
    fun gradedItems(): Set<String>

    /** The items in the order the user wants them worked on: the graded, then by prediction, then the newest. */
    fun itemsInOrder(): List<String>

    /** A value the source's jobs keep across restarts ([Job.state]), read outside of a job. */
    fun jobState(key: String): String?
}

/** Settings of one source, kept by the application. */
interface SourceSettings {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/**
 * The user's own actions on the site, as its pages show them — liked, in the library on a shelf,
 * read to some chapter, bookmarked. Kept with the user's grades, never with the scrape; shown, and
 * never read by the model: they are the user's verdict on the work ([SignalDef]).
 */
interface SiteSignals {
    /** Sets the [signal] of the item to [value]; null takes it away. */
    fun set(itemId: String, signal: String, value: String?)

    /** The items whose [signal] is set now. */
    fun withSignal(signal: String): Map<String, String>
}

/** The generic data of a source's items, written by the source, read by the application. */
interface ItemStore {
    /** Creates or updates an item; true when it is new or its version changed. */
    fun upsert(item: ItemHead, now: Instant): Boolean

    fun find(itemId: String): StoredItem?

    fun count(): Int

    /** The newest [ItemHead.updatedAt] of the catalogue. */
    fun latestUpdate(): Instant?

    /** Replaces the item's values of one facet. */
    fun setFacet(itemId: String, facet: String, values: List<FacetValue>)

    /** The names of facet values the items name by key; a name given before is replaced. */
    fun nameFacetValues(facet: String, names: Map<String, String>)

    /** What the site says the facet's values mean — a tag's own page describing it — by key; a description given before is replaced. */
    fun describeFacetValues(facet: String, descriptions: Map<String, String>)

    /** The keys of the item's values of every facet. */
    fun facets(itemId: String): Map<String, List<String>>

    /** Every item's keys of one facet, streamed. */
    fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit)

    /** Sets numbers of the item; a null takes one away. Numbers not named stay. */
    fun setNumbers(itemId: String, numbers: Map<String, Double?>)

    /** Sets texts of the item; a null or blank takes one away. Texts not named stay. */
    fun setTexts(itemId: String, texts: Map<String, String?>)

    fun texts(itemId: String): Map<String, String>

    /**
     * The item's pictures as the site lists them now, position 0 the cover. A picture whose
     * address changed is a new picture: its files and vector are forgotten.
     */
    fun setPictures(itemId: String, urls: List<String?>)

    /** Stores reviews of the item, a known one updated; none is deleted. */
    fun saveReviews(itemId: String, reviews: List<ReviewData>)

    fun reviewIds(itemId: String): Set<String>

    /** The texts of the item's stored reviews, by id: a review read whole is not replaced by its excerpt. */
    fun reviewContents(itemId: String): Map<String, String>

    /** Stores parts of the item's text, a known one updated; none is deleted. */
    fun saveParts(itemId: String, parts: List<PartData>)

    /** The parts of the item: id to whether its text is kept. */
    fun parts(itemId: String): Map<String, Boolean>
}

/** What every item has. */
data class ItemHead(
    val id: String,
    val url: String,
    val title: String,
    /** The release the item is at; the same for an item without versions. */
    val version: String = "",
    /** When the site says the item was last updated; null — unknown, the first time it is seen. */
    val updatedAt: Instant? = null,
)

data class StoredItem(
    val id: String,
    val url: String,
    val title: String,
    val version: String,
    val updatedAt: Instant,
    val firstSeenAt: Instant,
)

/** A value of a facet: its stable key, and its name when the page shows one. */
data class FacetValue(val key: String, val name: String? = null)

/** An opinion of someone else about the item. */
data class ReviewData(
    /** Unique within the item. */
    val id: String,
    val author: String?,
    /** The reviewer's own grade, 1..5, when given. */
    val stars: Double?,
    val content: String,
    val postedAt: Instant?,
)

/** A part of the item's text: a chapter. */
data class PartData(
    /** Unique within the item. */
    val id: String,
    /** The order of the parts; negative when the page does not tell it — a known part keeps its own. */
    val position: Int,
    val title: String?,
    /** The text; null when only the table of contents names the part. */
    val content: String?,
    val publishedAt: Instant? = null,
)
