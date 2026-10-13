package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.contenttype.StandardFacet
import io.github.vlsergey.recommend4me.i18n.Label

/**
 * What a source knows of its items beyond the title, and how the application uses each thing.
 * The labels are shown to the user in the language of the interface.
 */
class SourceSchema(
    val facets: List<FacetDef> = emptyList(),
    val numbers: List<NumberDef> = emptyList(),
    val texts: List<TextDef> = emptyList(),
    /** What the source calls the opinions of other people it keeps as reviews ("Reviews", "Comments"); null when it keeps none. */
    val reviewsLabel: Label? = null,
    /** What the source calls the parts of a work's text ("Chapters"); null when it keeps no text. */
    val partsLabel: Label? = null,
    /** Whether an item has a version that changes (a game's release) or is the same work whatever is added to it (a book). */
    val versioned: Boolean = false,
    /**
     * The key of the text ([texts]) where the site writes the characters and pairings of a work as
     * its author pleases (ficbook's "Пэйринг и персонажи"): shown above the characters and pairings the
     * application works out of it ([UniverseFacets][io.github.vlsergey.recommend4me.universe.UniverseFacets]).
     */
    val universeLine: String? = null,
    /**
     * The key of the facet ([facets]) the site tells by whether a work is fan fiction or an original
     * one (ficbook's fandoms, "Ориджиналы" among them; author.today's genres, "Фанфик" among them):
     * shown above the kind of the work's universe the application keeps.
     */
    val kindFrom: String? = null,
    /**
     * The pictures of a work tell what it is — a game's screenshots — and are shown before its
     * texts; otherwise its cover is a picture beside them (a book's).
     */
    val picturesTell: Boolean = false,
) {
    fun facet(key: String): FacetDef? = facets.firstOrNull { it.key == key }
    fun number(key: String): NumberDef? = numbers.firstOrNull { it.key == key }
    fun text(key: String): TextDef? = texts.firstOrNull { it.key == key }
}

/**
 * A categorical property of items: tags, an engine, a status, a developer, a fandom. An item has
 * any number of values of a facet; a value has a stable key and a name to show (they may be the
 * same).
 */
class FacetDef(
    val key: String,
    val label: Label,
    /** The list can be filtered by it: a drop-down of its values. */
    val filter: Boolean = false,
    /** The model reads it: every value seen in at least two rated works is a feature. */
    val feature: Boolean = true,
    /**
     * Facets of different sources of one content type with the same [shared] key are one facet for
     * the model and the filters ("author", "tag"); null — the facet is the source's alone.
     */
    val shared: String? = null,
    /** How much a match of a search in its values says of the item, 0 — not searched. */
    val searchWeight: Float = 0f,
    /** Shown on the card of an item. */
    val onCard: Boolean = false,
    /** The facet names who made the work — the author, the developer: a search for the whole name finds the work first. */
    val names: Boolean = false,
    /** An item without a value of it is listed under this name in the filter (a game with no status is "in development"); null — no such line. */
    val noneLabel: Label? = null,
    /**
     * The values a work should have are worked out from its text and its other values, and shown
     * to the user to confirm or reject — for tags a site leaves to its authors, who set them
     * carelessly or not at all.
     */
    val suggest: Boolean = false,
    /**
     * The values of the facet are worked out from the work's texts and its other values and given
     * to it as its own — the site's values are only examples to learn from (pairings and
     * characters a site leaves to its authors to write as they please). The values the site gives
     * that do not fit are shown marked; the user corrects the worked out ones as any other.
     */
    val infer: Boolean = false,
    /** The key of a text ([TextDef]) holding the facet's values as the site writes them: shown above the worked out values, and read by the suggester. */
    val original: String? = null,
    /** What the facet tells of the work, for the card to put it where it decides; null — one among the rest. */
    val role: FacetRole? = null,
    /** The standard facet of the content type this one is ([StandardFacet.asFacet]); null — the source's own. */
    val standard: StandardFacet? = null,
    /**
     * The facet is of the application's layer over the sources — the standard values worked out for
     * every work, the universe, the characters — and its values are corrected: the user's answers
     * are a patch of the source, whoever reads it. A facet of the site is the site's word as it is:
     * shown, read by the model, never corrected.
     */
    val editable: Boolean = false,
    /**
     * The key of a facet of the site whose values are this one's examples, matched by their names —
     * the site's tags of the standard tags: the model learns from them, a value the site has raises
     * the chance of the work's, one it lacks says little ([SuggestionTask.lackWeight][io.github.vlsergey.recommend4me.suggestion.SuggestionTask.lackWeight]).
     */
    val examplesFrom: String? = null,
    /**
     * Of a facet of the layer: the key of the facet of the site that is the site's own word on it —
     * its tags of the work's tags, its fandoms of the universes, its line of pairings and characters
     * of the characters — shown right above it, to answer by; null when the site has none.
     */
    val originalFacet: String? = null,
)

/**
 * What a facet tells of a work. The card of a work is the same for every source: the facts that
 * decide whether to read or play it first, the rest on demand — and a role says which are which.
 */
enum class FacetRole {
    /** Who made the work: the author, the developer — their other works are shown with the user's grades. */
    AUTHOR,

    /** The series the work is a part of — its other parts are shown with the user's grades. */
    SERIES,

    /** Whether the work is finished, still written, abandoned. */
    STATUS,

    /** What the work is made as or in: a novel, a story; an engine. */
    FORM,

    /** What is in the work: the genres, the tags, the fandom — the facts the user decides by. */
    CONTENT,

    /**
     * The site's own word on what the application works out for every work — its tags, its
     * fandoms, its pairings and characters as the site writes them: shown first, above the values
     * the user answers on, to answer by.
     */
    ORIGINAL,
}

/** What a number tells of a work, for the card to put it where it decides. */
enum class NumberRole {
    /** How long the work is: its characters, its words. */
    SIZE,

    /** How many people saw it: the views. */
    REACH,

    /** How many people liked it, or how well they rate it. */
    APPROVAL,
}

/** What a text of a work is, for the card to put it where it decides. */
enum class TextRole {
    /** What the work is about: the annotation, the overview — shown first. */
    DESCRIPTION,

    /** What the author adds: the notes, the dedication — shown folded. */
    NOTES,
}

/** How the model reads a number. */
enum class NumberScale {
    /** A count spread over orders of magnitude — views, likes: its logarithm. */
    LOG,

    /** A number of a fixed range — a rating of 0..5: as it is. */
    LINEAR,
}

class NumberDef(
    val key: String,
    val label: Label,
    val scale: NumberScale = NumberScale.LOG,
    val feature: Boolean = true,
    /** The list can be sorted by it. */
    val sortable: Boolean = false,
    val shared: String? = null,
    /** What the number tells of the work, for the card to put it where it decides; null — one among the rest. */
    val role: NumberRole? = null,
    /** The list can be limited to the works whose number lies within a range: from so many, up to so many. */
    val filterable: Boolean = false,
)

/**
 * A text of an item: an overview, an annotation, a changelog.
 *
 * Every text with a [block] is encoded by the text encoder and its vector is a block of the
 * model's input under that key: two sources' annotations of one content type share
 * `text:annotation`. A text without one is shown and searched only.
 */
class TextDef(
    val key: String,
    val label: Label,
    val block: String? = null,
    val searchWeight: Float = 0.5f,
    /** Encoded for the search by meaning even when the model does not read it. */
    val searchByMeaning: Boolean = block != null,
    /** Hidden under a spoiler in the interface until opened. */
    val spoiler: Boolean = false,
    /** What the text is, for the card to put it where it decides; null — a detail, shown folded. */
    val role: TextRole? = null,
)

/**
 * A kind of the user's own actions on the site ([SiteSignals]): its [label], and the names of its
 * values ([values], by the value as the source writes it); a value not named is shown as it is.
 * Shown, NEVER READ BY THE MODEL: what the user did with a work — read it, liked it, shelved it,
 * reacted to it, hid it, followed it — is their verdict on it, the very thing the grades teach.
 */
class SignalDef(val key: String, val label: Label, val values: Map<String, Label> = emptyMap())

/** A setting of a source the user can change in the interface: a cookie, a delay between requests. */
class SettingDef(
    val key: String,
    val label: Label,
    val kind: SettingKind = SettingKind.TEXT,
    val default: String? = null,
    /** Never sent back to the interface: only whether it is set. */
    val secret: Boolean = false,
    val hint: Label? = null,
)

enum class SettingKind { TEXT, NUMBER }
