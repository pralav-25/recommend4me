package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.UniversesApi
import io.github.vlsergey.recommend4me.api.model.CharacterInfo
import io.github.vlsergey.recommend4me.api.model.Inclusion
import io.github.vlsergey.recommend4me.api.model.LocalName
import io.github.vlsergey.recommend4me.api.model.UniverseClassInfo
import io.github.vlsergey.recommend4me.api.model.UniverseInfo
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import io.github.vlsergey.recommend4me.api.model.FoundUniverse as ApiFoundUniverse

private fun catalogueSilent(e: Exception) = Refusal.CATALOGUE_SILENT.refuse(
    HttpStatus.BAD_GATEWAY, "The catalogue did not answer: ${e.message}", "error" to (e.message ?: e.javaClass.simpleName),
)

@RestController
class UniversesController(private val stores: Stores, private val universes: Universes) : UniversesApi {

    /** The types that have universes — one dictionary of them all; 404 when none has. */
    private fun withUniverses() = stores.types.filter { it.type.universes }.takeIf { it.isNotEmpty() }
        ?: throw Refusal.NO_UNIVERSES.refuse(HttpStatus.NOT_FOUND, "No type has universes")

    /** The one dictionary of universes. */
    private fun dictionary() = withUniverses().first().universes

    override fun listUniverses(): ResponseEntity<List<UniverseInfo>> {
        val types = withUniverses()
        // How many works the user linked to each universe, over every source of every type
        val works = HashMap<String, Int>()
        types.flatMap { it.sources }.forEach { s ->
            s.corrections.allFacets().filter { it.facet == UniverseFacet.KEY && it.added }.forEach { works.merge(it.key, 1, Int::plus) }
        }
        return ResponseEntity.ok(types.first().universes.all().map { it.toApi(works[it.value] ?: 0) })
    }

    override fun addUniverse(universeRef: UniverseRef): ResponseEntity<UniverseInfo> {
        withUniverses()
        val added = try {
            universes.add(universeRef.catalogue, universeRef.universe)
        } catch (e: NoSuchElementException) {
            throw Refusal.NOT_IN_CATALOGUE.refuse(
                HttpStatus.NOT_FOUND, "The catalogue ${universeRef.catalogue} has no '${universeRef.universe}'", "universe" to universeRef.universe,
            )
        } catch (e: Exception) {
            throw catalogueSilent(e)
        }
        return ResponseEntity.ok(added.toApi(0))
    }

    override fun searchUniverses(query: String): ResponseEntity<List<ApiFoundUniverse>> {
        withUniverses()
        val found = try {
            universes.search(query.trim())
        } catch (e: Exception) {
            throw catalogueSilent(e)
        }
        return ResponseEntity.ok(found.map { ApiFoundUniverse(it.catalogue, it.entry.id, it.entry.name, it.entry.url, it.added, it.entry.description) })
    }

    override fun removeUniverse(catalogue: String, universe: String): ResponseEntity<Unit> {
        withUniverses()
        universes.remove(catalogue, universe)
        return ResponseEntity.noContent().build()
    }

    override fun listCharacters(catalogue: String, universe: String): ResponseEntity<List<CharacterInfo>> =
        ResponseEntity.ok(dictionary().entries(catalogue, universe).map { e ->
            val c = e.character
            CharacterInfo(
                character = c.id, value = UniverseFacet.valueOf(catalogue, c.id), names = c.names, url = c.url,
                classes = c.classes, included = e.included, description = c.description, choice = e.choice, localName = e.localName,
            )
        })

    override fun nameUniverseEntry(catalogue: String, entry: String, localName: LocalName): ResponseEntity<Unit> {
        withUniverses()
        universes.setLocalName(catalogue, entry, localName.name)
        return ResponseEntity.noContent().build()
    }

    override fun listUniverseClasses(catalogue: String, universe: String): ResponseEntity<List<UniverseClassInfo>> =
        ResponseEntity.ok(dictionary().classes(catalogue, universe).map {
            UniverseClassInfo(propertyClass = it.id, name = it.name, character = it.character, count = it.entries, included = it.included, choice = it.choice)
        })

    override fun chooseUniverseClass(catalogue: String, universe: String, propertyClass: String, inclusion: Inclusion): ResponseEntity<Unit> =
        chosen { universes.chooseClass(catalogue, universe, propertyClass, inclusion.included) }

    override fun resetUniverseClass(catalogue: String, universe: String, propertyClass: String): ResponseEntity<Unit> =
        chosen { universes.chooseClass(catalogue, universe, propertyClass, null) }

    override fun chooseUniverseEntry(catalogue: String, universe: String, character: String, inclusion: Inclusion): ResponseEntity<Unit> =
        chosen { universes.chooseEntry(catalogue, universe, character, inclusion.included) }

    override fun resetUniverseEntry(catalogue: String, universe: String, character: String): ResponseEntity<Unit> =
        chosen { universes.chooseEntry(catalogue, universe, character, null) }

    private fun chosen(choose: () -> Unit): ResponseEntity<Unit> {
        try {
            choose()
        } catch (e: NoSuchElementException) {
            throw Refusal.NO_SUCH_UNIVERSE.refuse(HttpStatus.NOT_FOUND, "No such universe in the dictionary: ${e.message}")
        }
        return ResponseEntity.noContent().build()
    }

    private fun StoredUniverse.toApi(works: Int) = UniverseInfo(
        catalogue = catalogue,
        universe = id,
        value = value,
        name = name,
        catalogueName = catalogueName,
        localName = localName,
        url = url,
        characters = characters,
        total = entries,
        works = works,
        addedAt = addedAt.atOffset(ZoneOffset.UTC),
        refreshedAt = refreshedAt.atOffset(ZoneOffset.UTC),
        description = description,
    )
}
