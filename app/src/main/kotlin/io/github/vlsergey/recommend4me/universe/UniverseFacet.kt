package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceSchema

/**
 * The facets the application gives every work of a content type with universes ([UniverseFacets]).
 * Their values are kept as the user's corrections of the work's source — the site has none — and
 * worked out by the model, as any facet's:
 *
 * - the kind — fan fiction, an alternative history, an original work — is the site's when it
 *   says, suggested otherwise;
 * - the universes are worked out of every universe of the dictionary for a work that is not known
 *   to have none, and given to it: the work's characters are chosen within them;
 * - the main characters are worked out of the characters of the work's universes and the original
 *   characters, the pairings of pairs of the work's characters — given to the work at once.
 *
 * All four are of the application's layer over the source: the user corrects them.
 */
object UniverseFacet {
    const val KEY = UniverseFacets.UNIVERSE

    /** The values of the kind of a work, with their names. */
    val KINDS = UniverseFacets.KIND_FACET.values!!.associate { it.key to it.label }

    /**
     * The facets of a source of a type with universes, each with the site's own word on it shown
     * right above it: the site's fandoms above the universes, its line of pairings and characters
     * above the characters (the pairings come right after them).
     */
    fun defs(schema: SourceSchema): List<FacetDef> {
        fun keyOf(standard: Any) = schema.facets.firstOrNull { it.standard === standard }?.key
        return listOf(
            // The site may say it, but it is the layer's: the user's answer is the work's kind
            UniverseFacets.KIND_FACET.asLayerFacet(examplesFrom = null),
            FacetDef(
                KEY, "Вселенная",
                filter = true,
                // One facet across the sources of the type: a universe is one whichever site a work is from
                shared = KEY, searchWeight = 0.9f, onCard = true, infer = true, editable = true,
                originalFacet = keyOf(UniverseFacets.FANDOM_FACET),
            ),
            FacetDef(
                UniverseFacets.CHARACTERS, "Главные персонажи",
                shared = UniverseFacets.CHARACTERS, searchWeight = 0.8f, infer = true, original = schema.universeLine, editable = true,
                originalFacet = keyOf(UniverseFacets.PAIRINGS_FACET),
            ),
            FacetDef(
                UniverseFacets.PAIRINGS, "Пэйринги",
                shared = UniverseFacets.PAIRINGS, searchWeight = 0.7f, infer = true, original = schema.universeLine, editable = true,
            ),
        )
    }

    /** The value of a universe or a character of a catalogue: "wikidata:Q8337". */
    fun valueOf(catalogue: String, id: String) = "$catalogue:$id"

    /** The catalogue and the id of a value; null when it is not one. */
    fun parse(value: String): Pair<String, String>? = value.indexOf(':').takeIf { it > 0 }?.let { value.substring(0, it) to value.substring(it + 1) }

    /** The original characters of a work, of no universe: the values every work may have besides its universes' characters. */
    val ORIGINAL_CHARACTERS = mapOf(
        "oc:female" to "ОЖП (оригинальный женский персонаж)",
        "oc:male" to "ОМП (оригинальный мужской персонаж)",
    )

    /** The sexes of the original characters. */
    val ORIGINAL_SEXES = mapOf("oc:female" to CharacterSex.FEMALE, "oc:male" to CharacterSex.MALE)

    /** The sex of every character of the dictionary of a type, and of the original characters: value to sex. */
    fun sexes(universes: UniverseRepository): Map<String, CharacterSex> {
        val out = HashMap<String, CharacterSex>(ORIGINAL_SEXES)
        universes.all().forEach { u ->
            universes.characters(u.catalogue, u.id).forEach { c -> c.sex?.let { out[valueOf(u.catalogue, c.id)] = it } }
        }
        return out
    }

    /** The page in its catalogue of every universe of the dictionary of a type and of every character of them: value to address. */
    fun pages(universes: UniverseRepository): Map<String, String> {
        val out = HashMap<String, String>()
        universes.all().forEach { u ->
            out[u.value] = u.url
            universes.characters(u.catalogue, u.id).forEach { c -> out[valueOf(u.catalogue, c.id)] = c.url }
        }
        return out
    }

    /** The sexes of a pairing's two characters, in the order of [CharacterSex]; null when the value is no pairing or a sex is not known. */
    fun sexesOf(pairing: String, sexes: Map<String, CharacterSex>): Pair<CharacterSex, CharacterSex>? {
        val (a, b) = members(pairing) ?: return null
        val x = sexes[a] ?: return null
        val y = sexes[b] ?: return null
        return if (x <= y) x to y else y to x
    }

    /** The sign of a pairing by its characters' sexes: ⚣ two men, ⚢ two women, ⚤ a man and a woman, ⚧ anyone else. */
    fun sign(sexes: Pair<CharacterSex, CharacterSex>): String = when (sexes) {
        CharacterSex.MALE to CharacterSex.MALE -> "⚣"
        CharacterSex.FEMALE to CharacterSex.FEMALE -> "⚢"
        CharacterSex.MALE to CharacterSex.FEMALE -> "⚤"
        else -> "⚧"
    }

    /** A pairing of two characters: one whatever their order. */
    fun pairing(a: String, b: String): String = listOf(a, b).sorted().let { (x, y) -> "pair:$x|$y" }

    /** The two characters of a pairing; null when the value is not one. */
    fun members(pairing: String): Pair<String, String>? =
        pairing.removePrefix("pair:").takeIf { it != pairing }?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
}
