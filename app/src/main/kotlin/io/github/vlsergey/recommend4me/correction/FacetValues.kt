package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagRepository

/** A value of a facet with its name and how many items have it, the user's corrections counted. */
data class FacetValueUse(val key: String, val name: String, val items: Int)

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
        return uses.map { (key, n) -> FacetValueUse(key, names[key] ?: key, n.coerceAtLeast(0)) }
            .sortedWith(compareByDescending<FacetValueUse> { it.items }.thenBy { it.name.lowercase() })
    }

    /**
     * The key of a value the user names: a value of the facet with that name or key, any case — or
     * a new one, its name in lower case. Of a facet of tags, a tag of that name, any case — or a
     * new normalized tag of that name.
     */
    fun keyOf(store: SourceStore, facet: String, name: String): String {
        val wanted = name.trim().lowercase()
        if (facet in store.tagFacets) {
            return store.names(facet).entries.firstOrNull { it.value.trim().lowercase() == wanted }?.key
                ?: TagRepository.key(store.tags.create(name.trim()))
        }
        return of(store, facet).firstOrNull { it.name.lowercase() == wanted || it.key.lowercase() == wanted }?.key ?: wanted
    }
}
