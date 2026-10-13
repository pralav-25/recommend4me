package io.github.vlsergey.recommend4me.source

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(StandardFacets::class.java)

/**
 * The standard facets of every source checked on start: a facet a source declares standard is one
 * of its content type's — else it is told of — and the values of a closed list are named as the
 * standard names them, whatever the site called them when they were read.
 */
@Component
class StandardFacets(private val stores: Stores) {

    @Order(1)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.types.forEach { t ->
            t.sources.forEach { s ->
                s.schema.facets.forEach { def ->
                    val standard = def.standard ?: return@forEach
                    if ((t.type.facets + t.type.layer).none { it.key == standard.key }) {
                        log.warn("{}: the facet {} is declared standard {}, which the type {} has not", s.id, def.key, standard.key, t.id)
                    }
                    standard.values?.let { values -> s.items.nameFacetValues(def.key, values.associate { it.key to it.label.ru }) }
                }
            }
        }
    }
}
