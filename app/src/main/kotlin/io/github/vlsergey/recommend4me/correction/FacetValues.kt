package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.springframework.http.HttpStatus

/**
 * A value of a facet with its name and how many items have it, the user's corrections counted;
 * [nameKey] — of a value of the application's own, the key of the words it is shown by ([SourceStore.nameKey]).
 */
data class FacetValueUse(val key: String, val name: String, val items: Int, val nameKey: String? = null)

/**
 * The values of a facet of a source, as the user picks one to add: the site's and the ones the
 * user made up, with how many items have each.
 */
object FacetValues {

    fun of(store: SourceStore, facet: String): List<FacetValueUse> {
        val uses = HashMap(store.items.valueUses(facet))
        val names = HashMap(store.names(facet))
        store.corrections.allFacets().filter { it.facet == facet }.forEach { c ->
            uses.merge(c.key, if (c.added) 1 else -1, Int::plus)
            c.name?.let { names.putIfAbsent(c.key, it) }
        }
        // A value named but on no item yet — a universe of the dictionary — is a value all the same
        names.keys.forEach { uses.putIfAbsent(it, 0) }
        // Of a facet of tags, the normalized tags alone: a key of a site's tag is of the time before them
        if (facet in store.tagFacets) uses.keys.removeIf { TagRepository.idOf(it) == null }
        return uses.map { (key, n) -> FacetValueUse(key, names[key] ?: key, n.coerceAtLeast(0), store.nameKey(facet, key)) }
            .sortedWith(compareByDescending<FacetValueUse> { it.items }.thenBy { it.name.lowercase() })
    }

    /**
     * The key of a value the user names: a value of the facet with that name or key, any case — or
     * a new one, its name in lower case. Of a facet of tags, a tag of that name, any case — or a
     * new normalized tag of that name. Of a facet of the dictionary's entries alone ([ENTRIES_ONLY]),
     * an entry of that name, or a refusal: a name made up is a value no dictionary knows.
     */
    fun keyOf(store: SourceStore, facet: String, name: String): String {
        val wanted = name.trim().lowercase()
        if (facet in store.tagFacets) {
            return store.names(facet).entries.firstOrNull { it.value.trim().lowercase() == wanted }?.key
                ?: TagRepository.key(store.tags.create(name.trim()))
        }
        val known = of(store, facet).filter { facet !in ENTRIES_ONLY || ':' in it.key }
            .firstOrNull { it.name.lowercase() == wanted || it.key.lowercase() == wanted }?.key
        if (known == null && facet in ENTRIES_ONLY) {
            throw Refusal.NOT_IN_DICTIONARY.refuse(HttpStatus.BAD_REQUEST, "'$name' is no entry of the dictionary for '$facet'", "value" to name.trim())
        }
        return known ?: wanted
    }

    /**
     * The facets whose values are entries of the dictionary alone, keyed by the catalogue — a
     * universe ("wikidata:Q8337"), a pair of its characters: chosen, never made of a name typed.
     */
    private val ENTRIES_ONLY = setOf(UniverseFacets.UNIVERSE, UniverseFacets.PAIRINGS)
}
