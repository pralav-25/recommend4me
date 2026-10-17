package io.github.vlsergey.recommend4me.universe

/**
 * THE NAMES OF THE LAYER OF UNIVERSES ARE THE DICTIONARY'S, whatever source shows them: a universe
 * and a character as the catalogue names them in the user's languages, a pairing by its two
 * characters with the sign of their sexes, an original character and a kind of universe by the
 * application's own words. A value the dictionary does not have — one the user made up — is named
 * by the source ([own]).
 */
class UniverseNames(private val dictionary: UniverseRepository) {

    /** The names of [keys] of [facet] — every value's when null; null for a facet not of the layer. */
    fun of(facet: String, keys: Collection<String>?, own: (Collection<String>?) -> Map<String, String>): Map<String, String>? {
        val named: Map<String, String> = when (facet) {
            UniverseFacets.KIND -> UniverseFacet.KINDS
            UniverseFacet.KEY -> dictionary.universeNames(keys)
            in UniverseFacet.CHARACTER_FACETS ->
                UniverseFacet.ORIGINAL_CHARACTERS + dictionary.characterNames(keys).mapValues { it.value.first }
            UniverseFacets.PAIRINGS -> pairings(keys)
            else -> return null
        }
        if (keys == null) return own(null) + named
        val found = keys.mapNotNull { k -> named[k]?.let { k to it } }.toMap()
        val unnamed = keys.filter { it !in found }
        return (if (unnamed.isEmpty()) emptyMap() else own(unnamed)) + found
    }

    /** What the catalogue says of [keys] of [facet] — a universe, a character: key to description; none of another facet. */
    fun descriptions(facet: String, keys: Collection<String>): Map<String, String> =
        if (facet == UniverseFacet.KEY || facet in UniverseFacet.CHARACTER_FACETS) dictionary.descriptions(keys) else emptyMap()

    /** Every name a character of [keys] of [facet] goes by in the user's languages, for a search by any of them; none of another facet. */
    fun allNames(facet: String, keys: Collection<String>): Map<String, List<String>> =
        if (facet in UniverseFacet.CHARACTER_FACETS) dictionary.characterAllNames(keys.filter { it !in UniverseFacet.ORIGINAL_CHARACTERS }) else emptyMap()

    /** "A / B" of the two characters — a man before a woman — with the sign of their sexes when the dictionary tells both. */
    private fun pairings(keys: Collection<String>?): Map<String, String> {
        val pairs = keys?.mapNotNull { p -> UniverseFacet.members(p)?.let { p to it } } ?: return emptyMap()
        val characters = dictionary.characterNames(pairs.flatMap { it.second.toList() }.filter { it !in UniverseFacet.ORIGINAL_CHARACTERS })
        fun name(c: String) = UniverseFacet.ORIGINAL_CHARACTERS[c] ?: characters[c]?.first
        val sexes = UniverseFacet.ORIGINAL_SEXES + characters.mapNotNull { (c, named) -> named.second?.let { c to it } }
        return pairs.mapNotNull { (p, members) ->
            // The key has them in the order of their keys; a man and a woman are written man first
            val (a, b) = members.let { (x, y) -> if (sexes[x] == CharacterSex.FEMALE && sexes[y] == CharacterSex.MALE) y to x else x to y }
            val written = "${name(a) ?: return@mapNotNull null} / ${name(b) ?: return@mapNotNull null}"
            p to (UniverseFacet.sexesOf(p, sexes)?.let { "${UniverseFacet.sign(it)} $written" } ?: written)
        }.toMap()
    }
}
