package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.layer.LayerValues
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(TagNormalization::class.java)

/**
 * WHAT THE TAGS ARE READ OVER: every source of every content type with a layer of tags learnt from
 * its site's tags — books and games alike — and the one dictionary of them ([tags]); [facet] the
 * key of the layer, one of every type (the standard tags).
 */
class TagScope(val sources: List<SourceStore>, val tags: TagRepository, val facet: String) {
    /** The content types the tags are of: told when the tags change, their models trained again. */
    val types: Set<String> = sources.map { it.type }.toSet()

    /** The name of the scope in the log. */
    val id = "tags"

    fun source(id: String): SourceStore? = sources.firstOrNull { it.id == id }
}

/**
 * EVERY SITE'S TAG IS BOUND TO A NORMALIZED TAG ([TagRepository]). A site's tag seen first is bound
 * to the normalized tag the site's tags of its name are bound to — "Магия" of one site and "магия"
 * of another one tag — when there is exactly one such; else to a new normalized tag of its own.
 * From then on the user binds it: merges tags, takes one out.
 */
@Component
class TagNormalization(private val stores: Stores) {

    /** The facet of the layer learnt from the sites' tags of a source; null for a source without one. */
    private fun layerOf(s: SourceStore): String? = s.schema.facets.firstOrNull { it.editable && it.examplesFrom != null }?.key

    /** Every source of every type with a layer of tags; null when none has one. */
    fun scope(): TagScope? {
        val sources = stores.types.flatMap { it.sources }.filter { layerOf(it) != null }
        val facet = sources.firstOrNull()?.let(::layerOf) ?: return null
        return TagScope(sources, sources.first().tags, facet)
    }

    /** The site's facet of tags of a source, the layer's [facet] learnt from it; null for a source without one. */
    fun siteFacetOf(s: SourceStore, facet: String): String? = s.schema.facets.firstOrNull { it.key == facet }?.examplesFrom

    /** Every site's tag not bound yet bound now; how many. */
    fun bindNew(): Int {
        val scope = scope() ?: return 0
        return bind(scope) { s, f -> s.items.valueUses(f).keys }
    }

    /**
     * The site's tags of the works [event] names bound at once, before anything reads the works: a
     * work captured is read with its tags.
     */
    @Order(-100)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val scope = scope() ?: return
        val source = scope.source(event.source) ?: return
        val f = siteFacetOf(source, scope.facet) ?: return
        val keys = source.items.facetsOf(event.ids).values.flatMap { it[f].orEmpty() }.toSet()
        if (keys.isNotEmpty()) bind(scope) { s, _ -> if (s.id == source.id) keys else emptySet() }
    }

    /**
     * The site's tags [keysOf] gives of every source bound, the ones not bound yet — of whichever
     * type: a name of a site of games and of one of books one tag; how many. One at a time: two at
     * once would make two tags of one name.
     */
    @Synchronized
    private fun bind(scope: TagScope, keysOf: (SourceStore, String) -> Set<String>): Int {
        val bound = scope.tags.bindings()
        val sites = scope.sources.mapNotNull { s -> siteFacetOf(s, scope.facet)?.let { s to it } }
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
                val id = byName[name]?.singleOrNull() ?: scope.tags.create().also {
                    byName.getOrPut(name) { HashSet() } += it
                    scope.tags.showAs(it, ShownName(written, NameRule.ALL))
                }
                scope.tags.bind(SiteTagRef(source, key), id, now)
                made++
            }
        }
        log.info("{} tags of the sites bound to normalized tags", made)
        return made
    }

    /** Before anything reads the tags: every site's tag not bound yet bound. */
    @Order(-10)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        try {
            bindNew()
        } catch (e: Exception) {
            log.error("The tags were not normalized", e)
        }
    }
}
