package io.github.vlsergey.recommend4me.layer

import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(LayerCorrections::class.java)

/**
 * THE USER CORRECTS THE APPLICATION'S LAYER, NOT THE SITE. Corrections made of a site's facet before
 * the layer had its own — a site's tag confirmed or taken away — are the user's word of the layer's
 * facet learnt from that one ([FacetDef.examplesFrom][io.github.vlsergey.recommend4me.source.FacetDef.examplesFrom]):
 * moved there on start, by the value's name. Any other correction of a site's facet is left as it
 * is and named in the log, for the user to decide on: no new one can be made.
 */
@Component
class LayerCorrections(private val stores: Stores) {

    @Order(2)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            val facets = s.schema.facets.associateBy { it.key }
            val layerOf = s.schema.facets.filter { it.editable && it.examplesFrom != null }.associateBy { it.examplesFrom!! }
            val stale = s.corrections.allFacets().filter { facets[it.facet]?.editable != true }
            if (stale.isEmpty()) return@forEach
            val now = Instant.now()
            var moved = 0
            stale.groupBy { it.facet }.forEach { (facet, list) ->
                val layer = layerOf[facet]
                if (layer == null) {
                    log.warn("{}: {} corrections of the site's facet {}, which is not corrected any more: {}", s.id, list.size, facet,
                        list.joinToString { "${it.itemId}:${if (it.added) "+" else "-"}${it.key}" })
                    return@forEach
                }
                val names = s.names(facet)
                val merges = s.tags.merges(layer.key)
                list.forEach { c ->
                    val name = names[c.key] ?: c.name ?: c.key
                    val key = LayerValues.keyOf(name, merges)
                    s.corrections.setFacet(c.itemId, layer.key, key, c.added, name.trim(), now)
                    s.corrections.unsetFacet(c.itemId, facet, c.key)
                    moved++
                }
            }
            if (moved > 0) log.info("{}: {} corrections of the site's values moved to the layer's", s.id, moved)
        }
    }
}
