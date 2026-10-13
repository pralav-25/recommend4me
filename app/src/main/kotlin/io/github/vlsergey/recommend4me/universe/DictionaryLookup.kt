package io.github.vlsergey.recommend4me.universe

/**
 * THE DICTIONARY OF A CONTENT TYPE LOOKED UP BY THE STATEMENTS OF ITS CATALOGUE ([CatalogueLookup]),
 * read whole at the start of one operation and handed to every part of it — never kept past it.
 */
class DictionaryLookup private constructor(
    /** Universe or character statements: (catalogue, property, value lowercased) to the values stating it. */
    private val universes: Map<Triple<String, String, String>, Set<String>>,
    private val characters: Map<Triple<String, String, String>, Set<String>>,
    /** The universes of the dictionary every character is in. */
    val characterUniverses: Map<String, Set<String>>,
) : CatalogueLookup {

    override fun universes(catalogue: String, property: String, value: String): Set<String> =
        universes[Triple(catalogue, property, value.lowercase())].orEmpty()

    override fun characters(catalogue: String, property: String, value: String): Set<String> =
        characters[Triple(catalogue, property, value.lowercase())].orEmpty()

    /** Whether the dictionary states anything: with nothing stated, no work has a draft. */
    val empty: Boolean get() = universes.isEmpty() && characters.isEmpty()

    companion object {
        fun load(dictionary: UniverseRepository): DictionaryLookup {
            fun index(statements: List<Triple<String, String, String>>) = statements
                .groupBy({ (entry, property, value) -> Triple(catalogueOf(entry), property, value.lowercase()) }, { it.first })
                .mapValues { it.value.toSet() }
            return DictionaryLookup(index(dictionary.universeStatements()), index(dictionary.characterStatements()), dictionary.characterUniverses())
        }

        /** The catalogue of a value of the dictionary: "wikidata" of "wikidata:Q174009". */
        private fun catalogueOf(value: String): String = value.substringBefore(':')
    }
}
