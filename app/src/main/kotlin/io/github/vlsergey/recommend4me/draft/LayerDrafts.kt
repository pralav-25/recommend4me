package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.universe.DictionaryLookup
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(LayerDrafts::class.java)

/**
 * THE DRAFTS OF THE LAYER, MADE: of every work of a source that asks the catalogue for statements
 * ([Source.catalogueProperties][io.github.vlsergey.recommend4me.source.Source.catalogueProperties]),
 * what the source drafts of it from its values on the site through the dictionary
 * ([Source.draft][io.github.vlsergey.recommend4me.source.Source.draft]) — and the universe of every
 * character drafted that is in one universe of the dictionary alone.
 *
 * Made again when the work is captured or refreshed — before anything reads it — and, of every work
 * of the type, when the dictionary changes and on start: the works whose drafts changed are then
 * read again as any changed work is.
 *
 * A draft is a value the work is given, marked so ([Chance.draftedFrom][io.github.vlsergey.recommend4me.suggestion.Chance.draftedFrom]);
 * the user's answers override it, kept apart: a draft taken away stays taken away, whatever it is
 * made of again.
 */
@Component
class LayerDrafts(private val stores: Stores, private val events: ApplicationEventPublisher) {

    /** After the sites' tags are bound ([TagNormalization][io.github.vlsergey.recommend4me.tag.TagNormalization]), before the model reads the works. */
    @Order(-50)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val store = stores.source(event.source)?.takeIf(::drafts) ?: return
        val lookup = DictionaryLookup.load(stores.typeOf(store.id).universes)
        if (event.ids.isNotEmpty()) {
            redraft(store, lookup, event.ids)
            return
        }
        // Every work of the source: the dictionary changed. The works whose drafts changed are read again
        val changed = redraft(store, lookup, store.items.keys().map { it.id })
        if (changed.isNotEmpty()) events.publishEvent(ItemsChanged(store.id, changed))
    }

    /** Every work drafted again on start: the dictionary or the rules may have changed while the application was down. */
    @Order(-10)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.filter(::drafts).forEach { store ->
            try {
                val changed = redraft(store, DictionaryLookup.load(stores.typeOf(store.id).universes), store.items.keys().map { it.id })
                if (changed.isNotEmpty()) events.publishEvent(ItemsChanged(store.id, changed))
            } catch (e: Exception) {
                log.error("{}: the drafts of the works were not made", store.id, e)
            }
        }
    }

    /** Whether the source drafts anything: it asks the catalogue for statements, and its type has universes. */
    private fun drafts(store: SourceStore): Boolean =
        store.source.catalogueProperties.isNotEmpty() && stores.typeOf(store.id).type.universes

    /** The drafts of the works [ids] made now and kept; the works whose drafts are not what they were. */
    private fun redraft(store: SourceStore, lookup: DictionaryLookup, ids: List<String>): List<String> {
        val started = System.currentTimeMillis()
        val made = HashMap<String, List<LayerDraft>>()
        ids.chunked(BATCH).forEach { chunk ->
            val facets = store.items.facetsOf(chunk)
            // The names of the values as written, of every facet of the chunk at once
            val names = facets.values.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (facet, keys) -> store.items.facetNames(facet, keys.flatten().distinct()) }
            chunk.forEach { id ->
                val values = facets[id].orEmpty().mapValues { (facet, keys) -> keys.map { FacetValue(it, names[facet]?.get(it)) } }
                made[id] = if (lookup.empty) emptyList() else withUniverses(store.source.draft(values, lookup), lookup)
            }
        }
        val before = store.drafts.ofItems(ids)
        val changed = ids.filter { id -> made[id].orEmpty().toSet() != before[id].orEmpty().toSet() }
        if (changed.isNotEmpty()) store.drafts.replace(changed.associateWith { made[it].orEmpty() })
        if (changed.isNotEmpty() || ids.size > 1) {
            log.info("{}: drafts of {} works made, {} changed, in {} ms", store.id, ids.size, changed.size, System.currentTimeMillis() - started)
        }
        return changed
    }

    /** The drafts with the universe of every character drafted that the dictionary has in one universe alone. */
    private fun withUniverses(drafts: List<LayerDraft>, lookup: DictionaryLookup): List<LayerDraft> {
        val universes = drafts.filter { it.facet == UniverseFacets.CHARACTERS }.mapNotNull { d ->
            lookup.characterUniverses[d.key]?.singleOrNull()?.let { LayerDraft(UniverseFacets.UNIVERSE, it, d.from) }
        }
        return (drafts + universes).distinctBy { it.facet to it.key }
    }

    companion object {
        private const val BATCH = 1000
    }
}
