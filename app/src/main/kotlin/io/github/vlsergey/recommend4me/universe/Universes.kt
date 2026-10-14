package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.SourceStore
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
 * THE DICTIONARY OF UNIVERSES, every content type's: the universes the user agreed on, with their
 * entries as the catalogue gave them — the characters and whatever else it says is in them, each
 * with its classes, the characters kept unless the user says otherwise. The catalogue is asked
 * only when the user asks — a search by the button, a universe added, its characters refreshed —
 * and what it answered is kept.
 *
 * The names of the universe and of its characters are written as the names of the values of the
 * facets "universe" and "characters" in every source of every type that has universes, so the works
 * name them as any value of a facet.
 */
@Service
class Universes(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    /** The languages the names of universes and characters are asked in, the user's first. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {
    /** The one dictionary, and every source of every type that has universes: what reads it. */
    private class Scope(val universes: UniverseRepository, val sources: List<SourceStore>)

    private fun scope(): Scope {
        val types = stores.types.filter { it.type.universes }
        val first = types.firstOrNull() ?: throw NoSuchElementException("No type has universes")
        return Scope(first.universes, types.flatMap { it.sources })
    }

    private fun catalogue(id: String): UniverseCatalogue = plugins.catalogues.firstOrNull { it.id == id } ?: throw NoSuchElementException("No catalogue $id")

    /** What every catalogue finds by [query]: the user picks among them. */
    fun search(query: String): List<FoundUniverse> {
        val known = scope().universes.all().map { it.catalogue to it.id }.toSet()
        return plugins.catalogues.flatMap { c ->
            c.findUniverses(query, languages).map { FoundUniverse(c.id, it, (c.id to it.id) in known) }
        }
    }

    /** Adds the universe to the dictionary — or refreshes it — with its characters as the catalogue gives them now. */
    fun add(catalogueId: String, id: String): StoredUniverse {
        val scope = scope()
        val catalogue = catalogue(catalogueId)
        val properties = wantedProperties(catalogueId)
        val entry = catalogue.universe(id, languages, properties) ?: throw NoSuchElementException("$catalogueId has no $id")
        val characters = catalogue.characters(id, languages, properties)
        val classes = catalogue.classes(characters.flatMap { it.classes }.toSet(), languages)
        scope.universes.save(catalogueId, entry, characters, classes, properties, Instant.now())
        // Its name and its characters' are the dictionary's, read from it by every source ([UniverseNames])
        val value = UniverseFacet.valueOf(catalogueId, id)
        val stored = scope.universes.find(catalogueId, id)!!
        log.info("The universe {} ({}) has {} entries of {} classes, {} of them kept as characters", entry.name, value, characters.size, classes.size, stored.characters)
        dictionaryChanged(scope)
        return stored
    }

    /** The statements of the catalogue's characters every plugin wants ([Source.catalogueProperties][io.github.vlsergey.recommend4me.source.Source.catalogueProperties]). */
    private fun wantedProperties(catalogueId: String): Set<String> = plugins.sources.flatMap { it.catalogueProperties[catalogueId].orEmpty() }.toSet()

    /**
     * The universes asked of their catalogue in other languages than the user's now — or before
     * the texts were kept by language — or for other statements of their characters than the
     * plugins want now, asked again, one by one, in the background: their names and descriptions
     * are then there in every language of the user, and their characters' statements the plugins'.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun askInNewLanguages() {
        if (stores.types.none { it.type.universes }) return
        val stale = scope().universes.all().filter { it.languages != languages || it.properties != wantedProperties(it.catalogue) }
        if (stale.isEmpty()) return
        Thread({
            stale.forEach { u ->
                try {
                    add(u.catalogue, u.id)
                } catch (e: Exception) {
                    log.warn("The universe {} was not asked again in {}: {}", u.value, languages, e.message)
                }
            }
        }, "universes-languages").apply { isDaemon = true }.start()
    }

    /**
     * The name the user gives a universe or a character of [catalogue] ([id]) over the catalogue's —
     * shown everywhere in its place, read in the texts as one more name of it; null or blank takes it
     * back. A character is named in every universe it is in.
     */
    fun setLocalName(catalogueId: String, id: String, name: String?) {
        val scope = scope()
        scope.universes.setLocalName(catalogueId, id, name, Instant.now())
        log.info("{} named {}", UniverseFacet.valueOf(catalogueId, id), name?.trim()?.takeIf { it.isNotEmpty() } ?: "as the catalogue names it")
        dictionaryChanged(scope)
    }

    /** The user's word on a class of the universe's entries — kept, left out, or null: as the catalogue says. */
    fun chooseClass(catalogueId: String, id: String, cls: String, included: Boolean?) {
        val scope = scope()
        scope.universes.find(catalogueId, id) ?: throw NoSuchElementException("No universe $catalogueId:$id")
        scope.universes.chooseClass(catalogueId, id, cls, included)
        dictionaryChanged(scope)
    }

    /** The user's word on one entry of the universe — kept, left out, or null: as its classes say. */
    fun chooseEntry(catalogueId: String, id: String, character: String, included: Boolean?) {
        val scope = scope()
        scope.universes.find(catalogueId, id) ?: throw NoSuchElementException("No universe $catalogueId:$id")
        scope.universes.chooseEntry(catalogueId, id, character, included)
        dictionaryChanged(scope)
    }

    /** Takes the universe out of the dictionary, and with it every work's link to it. */
    fun remove(catalogueId: String, id: String) {
        val scope = scope()
        scope.universes.remove(catalogueId, id)
        val value = UniverseFacet.valueOf(catalogueId, id)
        scope.sources.forEach { s ->
            val linked = s.corrections.removeFacetValue(UniverseFacet.KEY, value)
            if (linked.isNotEmpty()) events.publishEvent(ItemsChanged(s.id, linked))
        }
        dictionaryChanged(scope)
    }

    /** The dictionary is what the model chooses from: every source that reads it is worked out again. */
    private fun dictionaryChanged(scope: Scope) {
        scope.sources.forEach { events.publishEvent(ItemsChanged(it.id, emptyList())) }
    }
}
