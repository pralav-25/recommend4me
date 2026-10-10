package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Instant

private val log = LoggerFactory.getLogger(Universes::class.java)

/** An entry a catalogue found, and whether the dictionary has it. */
class FoundUniverse(val catalogue: String, val entry: UniverseEntry, val added: Boolean)

/**
 * THE DICTIONARY OF UNIVERSES of a content type: the universes the user agreed on, with their
 * entries as the catalogue gave them — the characters and whatever else it says is in them, each
 * with its classes, the characters kept unless the user says otherwise. The catalogue is asked
 * only when the user asks — a search by the button, a universe added, its characters refreshed —
 * and what it answered is kept.
 *
 * The names of the universe and of its characters are written as the names of the values of the
 * facets "universe" and "characters" in every source of the type, so the works name them as any
 * value of a facet.
 */
@Service
class Universes(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    /** The languages the names of universes and characters are asked in, the user's first. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {
    private fun type(id: String): TypeStore = stores.type(id)?.takeIf { it.type.universes } ?: throw NoSuchElementException("No universes for $id")

    private fun catalogue(id: String): UniverseCatalogue = plugins.catalogues.firstOrNull { it.id == id } ?: throw NoSuchElementException("No catalogue $id")

    /** What every catalogue finds by [query]: the user picks among them. */
    fun search(typeId: String, query: String): List<FoundUniverse> {
        val known = type(typeId).universes.all().map { it.catalogue to it.id }.toSet()
        return plugins.catalogues.flatMap { c ->
            c.findUniverses(query, languages).map { FoundUniverse(c.id, it, (c.id to it.id) in known) }
        }
    }

    /** Adds the universe to the dictionary — or refreshes it — with its characters as the catalogue gives them now. */
    fun add(typeId: String, catalogueId: String, id: String): StoredUniverse {
        val type = type(typeId)
        val catalogue = catalogue(catalogueId)
        val entry = catalogue.universe(id, languages) ?: throw NoSuchElementException("$catalogueId has no $id")
        val characters = catalogue.characters(id, languages)
        val classes = catalogue.classes(characters.flatMap { it.classes }.toSet(), languages)
        type.universes.save(catalogueId, entry, characters, classes, Instant.now())
        val value = UniverseFacet.valueOf(catalogueId, id)
        val names = characters.associate { UniverseFacet.valueOf(catalogueId, it.id) to it.names.first() }
        type.sources.forEach {
            it.items.nameFacetValues(UniverseFacet.KEY, mapOf(value to entry.name))
            UniverseFacet.CHARACTER_FACETS.forEach { facet -> it.items.nameFacetValues(facet, names) }
        }
        val stored = type.universes.find(catalogueId, id)!!
        log.info("{}: the universe {} ({}) has {} entries of {} classes, {} of them kept as characters", typeId, entry.name, value, characters.size, classes.size, stored.characters)
        dictionaryChanged(type)
        return stored
    }

    /**
     * The universes asked of their catalogue in other languages than the user's now — or before
     * the texts were kept by language — asked again, one by one, in the background: their names and
     * descriptions are then there in every language of the user.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun askInNewLanguages() {
        val stale = stores.types.filter { it.type.universes }.flatMap { t -> t.universes.all().filter { it.languages != languages }.map { t to it } }
        if (stale.isEmpty()) return
        Thread({
            stale.forEach { (t, u) ->
                try {
                    add(t.id, u.catalogue, u.id)
                } catch (e: Exception) {
                    log.warn("{}: the universe {} was not asked again in {}: {}", t.id, u.value, languages, e.message)
                }
            }
        }, "universes-languages").apply { isDaemon = true }.start()
    }

    /** The user's word on a class of the universe's entries — kept, left out, or null: as the catalogue says. */
    fun chooseClass(typeId: String, catalogueId: String, id: String, cls: String, included: Boolean?) {
        val type = type(typeId)
        type.universes.find(catalogueId, id) ?: throw NoSuchElementException("No universe $catalogueId:$id")
        type.universes.chooseClass(catalogueId, id, cls, included)
        dictionaryChanged(type)
    }

    /** The user's word on one entry of the universe — kept, left out, or null: as its classes say. */
    fun chooseEntry(typeId: String, catalogueId: String, id: String, character: String, included: Boolean?) {
        val type = type(typeId)
        type.universes.find(catalogueId, id) ?: throw NoSuchElementException("No universe $catalogueId:$id")
        type.universes.chooseEntry(catalogueId, id, character, included)
        dictionaryChanged(type)
    }

    /** Takes the universe out of the dictionary, and with it every work's link to it. */
    fun remove(typeId: String, catalogueId: String, id: String) {
        val type = type(typeId)
        type.universes.remove(catalogueId, id)
        val value = UniverseFacet.valueOf(catalogueId, id)
        type.sources.forEach { s ->
            val linked = s.corrections.removeFacetValue(UniverseFacet.KEY, value)
            if (linked.isNotEmpty()) events.publishEvent(ItemsChanged(s.id, linked))
        }
        dictionaryChanged(type)
    }

    /** The dictionary is what the model chooses from: every source of the type is worked out again. */
    private fun dictionaryChanged(type: TypeStore) {
        type.sources.forEach { events.publishEvent(ItemsChanged(it.id, emptyList())) }
    }
}
