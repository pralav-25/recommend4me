package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.layer.LayerValues
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(TagNormalization::class.java)

/**
 * EVERY SITE'S TAG IS BOUND TO A NORMALIZED TAG ([TagRepository]). A site's tag seen first is bound
 * to the normalized tag the site's tags of its name are bound to — "Магия" of one site and "магия"
 * of another one tag — when there is exactly one such; else to a new normalized tag of its own.
 * From then on the user binds it: merges tags, takes one out.
 */
@Component
class TagNormalization(private val stores: Stores) {

    /** The facet of the layer learnt from the sites' tags of a type, and of every source the site's facet of tags; null for a type without one. */
    fun facetOf(type: TypeStore): String? =
        type.sources.firstNotNullOfOrNull { s -> s.schema.facets.firstOrNull { it.editable && it.examplesFrom != null } }?.key

    /** The site's facet of tags of a source, the layer's [facet] learnt from it; null for a source without one. */
    fun siteFacetOf(s: SourceStore, facet: String): String? = s.schema.facets.firstOrNull { it.key == facet }?.examplesFrom

    /** Every site's tag of the type not bound yet bound now; how many. */
    fun bindNew(type: TypeStore): Int {
        val facet = facetOf(type) ?: return 0
        return bind(type, facet) { s, f -> s.items.valueUses(f).keys }
    }

    /**
     * The site's tags of the works [event] names bound at once, before anything reads the works: a
     * work captured is read with its tags.
     */
    @Order(-100)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val type = stores.typeOf(event.source)
        val facet = facetOf(type) ?: return
        val source = type.source(event.source) ?: return
        val f = siteFacetOf(source, facet) ?: return
        val keys = source.items.facetsOf(event.ids).values.flatMap { it[f].orEmpty() }.toSet()
        if (keys.isNotEmpty()) bind(type, facet) { s, _ -> if (s.id == source.id) keys else emptySet() }
    }

    /**
     * The site's tags [keysOf] gives of every source of the type bound, the ones not bound yet; how
     * many. One at a time: two at once would make two tags of one name.
     */
    @Synchronized
    private fun bind(type: TypeStore, facet: String, keysOf: (SourceStore, String) -> Set<String>): Int {
        val bound = type.tags.bindings()
        val sites = type.sources.mapNotNull { s -> siteFacetOf(s, facet)?.let { s to it } }
        val fresh = sites.associate { (s, f) -> s.id to keysOf(s, f).filter { SiteTagRef(s.id, it) !in bound } }.filterValues { it.isNotEmpty() }
        if (fresh.isEmpty()) return 0
        val now = Instant.now()
        val namesOf = sites.associate { (s, f) -> s.id to s.names(f) }
        // The normalized tags every name is bound to, of every site
        val byName = HashMap<String, MutableSet<Long>>()
        bound.forEach { (ref, id) -> byName.getOrPut(LayerValues.keyOf(namesOf[ref.source]?.get(ref.tag) ?: ref.tag)) { HashSet() } += id }
        var made = 0
        fresh.forEach { (source, keys) ->
            val names = namesOf.getValue(source)
            keys.forEach { key ->
                val written = (names[key] ?: key).trim()
                val name = LayerValues.keyOf(written)
                val id = byName[name]?.singleOrNull() ?: type.tags.create().also {
                    byName.getOrPut(name) { HashSet() } += it
                    type.tags.showAs(it, ShownName(written, NameRule.ALL))
                }
                type.tags.bind(SiteTagRef(source, key), id, now)
                made++
            }
        }
        log.info("{}: {} tags of the sites bound to normalized tags", type.id, made)
        return made
    }

    /** Before anything reads the tags: every site's tag not bound yet bound. */
    @Order(-10)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.types.forEach { type ->
            try {
                bindNew(type)
            } catch (e: Exception) {
                log.error("{}: the tags were not normalized", type.id, e)
            }
        }
    }
}
