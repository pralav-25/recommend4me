package io.github.vlsergey.recommend4me.universe

/**
 * THE DICTIONARY OF UNIVERSES LOOKED UP BY A STATEMENT OF ITS CATALOGUE — the universes and the
 * characters stating a value of a property the plugins asked for ([Source.catalogueProperties][io.github.vlsergey.recommend4me.source.Source.catalogueProperties]):
 * "Hermione Granger" of P8419, the tag of Archive of Our Own. The values are the layer's keys —
 * "wikidata:Q174009" — any case of the value alike; none when nothing states it.
 */
interface CatalogueLookup {
    /** The universes of the dictionary stating [value] of [property] — of themselves or of a work within them. */
    fun universes(catalogue: String, property: String, value: String): Set<String>

    /** The characters of the dictionary's universes stating [value] of [property]. */
    fun characters(catalogue: String, property: String, value: String): Set<String>
}

/**
 * A value of the application's layer a source DRAFTS of a work from the work's own values on the
 * site, as the dictionary matches them — a character the site tags the work with, its pairing, its
 * universe — before the user says anything: the work is given it, marked as drafted; the user's
 * answers override it. [facet]: of the layer ([UniverseFacets.UNIVERSE], [UniverseFacets.CHARACTERS],
 * [UniverseFacets.PAIRINGS]); [key]: the layer's value; [from]: the site's value it was read from,
 * as written.
 */
data class LayerDraft(val facet: String, val key: String, val from: String)
