package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.i18n.Label
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

    val AUTHOR = StandardFacet("author", Label("Author", "Автор", "Autor"), role = FacetRole.AUTHOR, onCard = true, names = true, searchWeight = 1.0f)

    /** Whether the whole of the work is written: of a site that tells only that, finished or in progress. */
    object Status {
        val FINISHED = StandardValue("finished", Label("Finished", "Завершено", "Završeno"))
        val IN_PROGRESS = StandardValue("in-progress", Label("In progress", "В процессе", "U toku"))
        val FROZEN = StandardValue("frozen", Label("Frozen", "Заморожено", "Zamrznuto"))
    }

    val STATUS = StandardFacet(
        "status", Label("Status", "Статус", "Status"),
        values = listOf(Status.FINISHED, Status.IN_PROGRESS, Status.FROZEN),
        role = FacetRole.STATUS, filter = true, onCard = true,
    )

    /** The tags as the site writes them: the site's word, read by the model, the examples of [TAGS]. */
    val TAG = StandardFacet("tag", Label("Tags on the site", "Теги на сайте", "Tagovi na sajtu"), role = FacetRole.ORIGINAL, searchWeight = 0.9f)

    /** The fandoms as the site writes them: the site's word of what the universes of the work are. */
    val FANDOM = UniverseFacets.FANDOM_FACET

    /** The pairings and characters as the site writes them, one field ([UniverseFacets.PAIRINGS_FACET]). */
    val PAIRINGS = UniverseFacets.PAIRINGS_FACET

    /**
     * The work's tags: of every tag of every site, one by its name — worked out from everything the
     * site says of the work, its own tags first of all, and corrected by the user.
     */
    val TAGS = StandardFacet("tags", Label("Tags", "Теги", "Tagovi"), role = FacetRole.CONTENT, searchWeight = 0.9f, infer = true, examples = TAG)

    /** How many chapters — parts — the work's text is in, whatever the site calls them: one filter of every site. */
    val PARTS = StandardNumber("parts", Label("Chapters", "Глав", "Poglavlja"), NumberScale.LOG, filterable = true)

    /** The value of a signal that is either set or not. */
    val YES = StandardValue("yes", Label("yes", "да", "da"))

    /** The user marked the work read on the site: on its shelf of the finished, with its box "read" ticked. */
    val READ = StandardSignal("read", Label("Read", "Прочитано", "Pročitano"), listOf(YES))

    /** The user liked the work on the site. */
    val LIKED = StandardSignal("liked", Label("Liked", "Понравилось", "Svidelo mi se"), listOf(YES))

    /** The shelves of a user's library on a site. */
    object Shelf {
        val READING = StandardValue("reading", Label("reading", "читаю", "čitam"))
        val SAVED = StandardValue("saved", Label("saved for later", "отложено", "za kasnije"))
        val FINISHED = StandardValue("finished", Label("finished", "прочитано", "pročitano"))
        val DISLIKED = StandardValue("disliked", Label("disliked", "не понравилось", "nije mi se svidelo"))
        val PURCHASED = StandardValue("purchased", Label("purchased", "куплено", "kupljeno"))
    }

    /** The shelf of the user's library on the site the work is on; the shelf of the finished also sets [READ]. */
    val SHELF = StandardSignal(
        "shelf", Label("Shelf", "Полка", "Polica"),
        listOf(Shelf.READING, Shelf.SAVED, Shelf.FINISHED, Shelf.DISLIKED, Shelf.PURCHASED),
    )

    val TYPE = ContentType(
        id = ID,
        title = Label("Books", "Книги", "Knjige"),
        grades = listOf(
            Label("Dislike", "Не нравится", "Ne sviđa mi se"),
            Label("Worth a read", "Можно почитать", "Može da se čita"),
            Label("Liked it overall", "В целом понравилась", "Uglavnom mi se svidela"),
            Label("Very good", "Очень хорошая", "Veoma dobra"),
            Label("Want more", "Хочу ещё", "Hoću još"),
        ),
        verb = Label("read", "читать", "čitati"),
        universes = true,
        facets = listOf(AUTHOR, STATUS, UniverseFacets.KIND_FACET, TAG, FANDOM, PAIRINGS),
        layer = listOf(TAGS),
        signals = listOf(READ, LIKED, SHELF),
        numbers = listOf(PARTS, StandardNumbers.LIKES),
    )
}
