package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.universe.UniverseFacets

/**
 * BOOKS — novels, fan fiction, serials of any site — and their standard facets: what every source
 * of books maps its own facets onto. What the work is about is the text of the role
 * [DESCRIPTION][io.github.vlsergey.recommend4me.source.TextRole.DESCRIPTION] of every source.
 */
object Books {
    const val ID = "books"

    val AUTHOR = StandardFacet("author", role = FacetRole.AUTHOR, onCard = true, names = true, searchWeight = 1.0f)

    /** Whether the whole of the work is written: of a site that tells only that, finished or in progress. */
    object Status {
        val FINISHED = StandardValue("finished", "Завершено")
        val IN_PROGRESS = StandardValue("in-progress", "В процессе")
        val FROZEN = StandardValue("frozen", "Заморожено")
    }

    val STATUS = StandardFacet(
        "status",
        values = listOf(Status.FINISHED, Status.IN_PROGRESS, Status.FROZEN),
        role = FacetRole.STATUS, filter = true, onCard = true,
    )

    /**
     * Whom the work's relationships are between — AO3's category, ficbook's direction: of a site
     * that says the work has none ("No category", "Не определено"), no value.
     */
    object Direction {
        val GEN = StandardValue("gen", "Джен")
        val HET = StandardValue("het", "Гет")
        val SLASH = StandardValue("slash", "Слэш")
        val FEMSLASH = StandardValue("femslash", "Фемслэш")
        val MIXED = StandardValue("mixed", "Смешанная")
        val OTHER = StandardValue("other", "Другие виды отношений")
        val ARTICLE = StandardValue("article", "Статья")
    }

    val DIRECTION = StandardFacet(
        "direction",
        values = listOf(Direction.GEN, Direction.HET, Direction.SLASH, Direction.FEMSLASH, Direction.MIXED, Direction.OTHER, Direction.ARTICLE),
        role = FacetRole.CONTENT, filter = true, onCard = true,
    )

    /** The tags as the site writes them ([StandardTags.TAG]). */
    val TAG = StandardTags.TAG

    /** The fandoms as the site writes them: the site's word of what the universes of the work are. */
    val FANDOM = UniverseFacets.FANDOM_FACET

    /** The pairings and characters as the site writes them, one field ([UniverseFacets.PAIRINGS_FACET]). */
    val PAIRINGS = UniverseFacets.PAIRINGS_FACET

    /** The work's tags, the normalized ones ([StandardTags.TAGS]). */
    val TAGS = StandardTags.TAGS

    /** How many chapters — parts — the work's text is in, whatever the site calls them: one filter of every site. */
    val PARTS = StandardNumber("parts", NumberScale.LOG, sortable = true, filterable = true)

    /** The value of a signal that is either set or not. */
    val YES = StandardValue("yes", "да")

    /** The user marked the work read on the site: on its shelf of the finished, with its box "read" ticked. */
    val READ = StandardSignal("read", listOf(YES))

    /** The user liked the work on the site. */
    val LIKED = StandardSignal("liked", listOf(YES))

    /** The shelves of a user's library on a site. */
    object Shelf {
        val READING = StandardValue("reading", "читаю")
        val SAVED = StandardValue("saved", "отложено")
        val FINISHED = StandardValue("finished", "прочитано")
        val DISLIKED = StandardValue("disliked", "не понравилось")
        val PURCHASED = StandardValue("purchased", "куплено")
    }

    /** The shelf of the user's library on the site the work is on; the shelf of the finished also sets [READ]. */
    val SHELF = StandardSignal("shelf", listOf(Shelf.READING, Shelf.SAVED, Shelf.FINISHED, Shelf.DISLIKED, Shelf.PURCHASED))

    val TYPE = ContentType(
        id = ID,
        universes = true,
        facets = listOf(AUTHOR, STATUS, DIRECTION, UniverseFacets.KIND_FACET, TAG, FANDOM, PAIRINGS),
        layer = listOf(TAGS),
        signals = listOf(READ, LIKED, SHELF),
        numbers = listOf(PARTS, StandardNumbers.WORDS, StandardNumbers.LIKES, StandardNumbers.VIEWS),
    )
}
