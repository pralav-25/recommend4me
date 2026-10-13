package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.contenttype.StandardFacet
import io.github.vlsergey.recommend4me.contenttype.StandardValue
import io.github.vlsergey.recommend4me.source.FacetRole

/**
 * The facets the application gives every work of a content type with universes
 * ([ContentType.universes][io.github.vlsergey.recommend4me.contenttype.ContentType.universes]),
 * whichever site it is from: what a source names in its page decor or reads of the user's links.
 *
 * - [KIND]: whether the work is fan fiction ([FANFICTION]), set in our world as it is ([REALISM]),
 *   an alternative history of our world ([ALTERNATIVE_HISTORY]) or a world of its own ([ORIGINAL]) —
 *   the one a source may say itself: a site's fandom "originals", its genre "fan fiction";
 * - [UNIVERSE]: the universes of the dictionary the work is fan fiction of — one or several; a
 *   realistic work, an alternative history and an original work have none;
 * - [CHARACTERS]: its main characters, of those universes;
 * - [SECONDARY_CHARACTERS]: its secondary characters, of those universes — never one of its main ones;
 * - [PAIRINGS]: its pairings, each two of its characters ("pair:<character>|<character>", the two
 *   in the order of their keys).
 *
 * Of the last three the site gives none: the user links a work, the model works the rest out from
 * the line the site writes the characters and pairings in ([SourceSchema.universeLine][io.github.vlsergey.recommend4me.source.SourceSchema.universeLine]),
 * the tags, the description and the chapters.
 */
object UniverseFacets {
    const val KIND = "kind"
    const val FANFICTION = "fanfiction"
    const val REALISM = "realism"
    const val ALTERNATIVE_HISTORY = "alternative-history"
    const val ORIGINAL = "original"

    /** The kinds of a work that has no universe of another's: its world is ours, ours changed, or its own. */
    val UNIVERSELESS = setOf(REALISM, ALTERNATIVE_HISTORY, ORIGINAL)

    /**
     * [KIND] as the standard facet of every type with universes: fan fiction, realism, an
     * alternative history or an original work, nothing else.
     */
    val KIND_FACET = StandardFacet(
        KIND,
        values = listOf(
            StandardValue(FANFICTION, "Фанфик"),
            StandardValue(REALISM, "Реализм"),
            StandardValue(ALTERNATIVE_HISTORY, "Альтернативная история"),
            StandardValue(ORIGINAL, "Оригинальная вселенная"),
        ),
        filter = true, onCard = true, suggest = true,
    )

    const val UNIVERSE = "universe"
    const val CHARACTERS = "characters"
    const val SECONDARY_CHARACTERS = "secondary-characters"
    const val PAIRINGS = "pairings"

    /** The fandoms as a site writes them: the site's word of what the universes of the work are, shown above them. */
    val FANDOM_FACET = StandardFacet(
        "fandom", role = FacetRole.ORIGINAL, filter = true, onCard = true, searchWeight = 0.9f,
    )

    /**
     * The pairings and characters as a site writes them, one field: every entry of the site's line
     * as it is — "Brian/Aveline", "Frida" — a pairing one whatever the order of its names. The
     * site's word of what the work's characters and pairings are, shown above them.
     */
    val PAIRINGS_FACET = StandardFacet(
        "pairing", role = FacetRole.ORIGINAL, searchWeight = 0.8f,
    )
}
