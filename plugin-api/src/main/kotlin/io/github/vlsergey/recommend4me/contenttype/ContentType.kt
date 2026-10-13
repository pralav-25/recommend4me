package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.i18n.Label

/**
 * A kind of works ranked together: graded on one scale and put in order by one model, whichever
 * source they came from. A plugin may declare one as a bean; the application declares `games`
 * and `books`.
 *
 * [title] and the labels of [grades] are shown to the user in the language of the interface.
 */
class ContentType(
    val id: String,
    val title: Label,
    /** The labels of the grades 1..5, lowest first: what "1" means for a game is not what it means for a book. */
    val grades: List<Label>,
    /** What the user does with a work: "play", "read" — for the hints of the interface. */
    val verb: Label,
    /**
     * Works of the type may be written in the universes of others — fan fiction: the type has a
     * dictionary of universes and their characters, and every work of it the facet "universe".
     */
    val universes: Boolean = false,
    /** The facets every source of the type maps its own onto ([StandardFacet]). */
    val facets: List<StandardFacet> = emptyList(),
    /**
     * THE APPLICATION'S LAYER OVER THE SOURCES: the facets every work of the type is given whichever
     * site it is from, worked out by the model from everything the site says and corrected by the
     * user — the standard tags. No site sets them.
     */
    val layer: List<StandardFacet> = emptyList(),
    /** The user's actions on a site every source of the type tells alike ([StandardSignal]). */
    val signals: List<StandardSignal> = emptyList(),
    /** The numbers every source of the type that has them tells alike ([StandardNumber]): one filter, one feature. */
    val numbers: List<StandardNumber> = emptyList(),
) {
    init {
        require(grades.size == 5) { "A content type labels five grades, $id labels ${grades.size}" }
    }
}
