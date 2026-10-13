package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.UniversesApi
import io.github.vlsergey.recommend4me.api.model.CharacterInfo
import io.github.vlsergey.recommend4me.api.model.Inclusion
import io.github.vlsergey.recommend4me.api.model.LocalName
import io.github.vlsergey.recommend4me.api.model.UniverseClassInfo
import io.github.vlsergey.recommend4me.api.model.UniverseInfo
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.i18n.failure
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import io.github.vlsergey.recommend4me.api.model.FoundUniverse as ApiFoundUniverse

private val CATALOGUE_SILENT = Label("The catalogue did not answer: %s", "Справочник не ответил: %s", "Katalog nije odgovorio: %s")

@RestController
class UniversesController(private val stores: Stores, private val universes: Universes) : UniversesApi {

    private fun typeOf(type: String) = stores.type(type)?.takeIf { it.type.universes }
        ?: throw failure(HttpStatus.NOT_FOUND, Label("The type “%s” has no universes", "У типа «%s» нет вселенных", "Vrsta „%s” nema univerzume"), type)

    override fun listUniverses(type: String): ResponseEntity<List<UniverseInfo>> {
        val t = typeOf(type)
        // How many works the user linked to each universe, over every source of the type
        val works = HashMap<String, Int>()
        t.sources.forEach { s -> s.corrections.allFacets().filter { it.facet == UniverseFacet.KEY && it.added }.forEach { works.merge(it.key, 1, Int::plus) } }
        return ResponseEntity.ok(t.universes.all().map { it.toApi(works[it.value] ?: 0) })
    }

    override fun addUniverse(type: String, universeRef: UniverseRef): ResponseEntity<UniverseInfo> {
        typeOf(type)
        val added = try {
            universes.add(type, universeRef.catalogue, universeRef.universe)
        } catch (e: NoSuchElementException) {
            throw failure(HttpStatus.NOT_FOUND, Label("The catalogue has no “%s”", "В справочнике нет «%s»", "Katalog nema „%s”"), universeRef.universe)
        } catch (e: Exception) {
            throw failure(HttpStatus.BAD_GATEWAY, CATALOGUE_SILENT, e.message)
        }
        return ResponseEntity.ok(added.toApi(0))
    }

    override fun searchUniverses(type: String, query: String): ResponseEntity<List<ApiFoundUniverse>> {
        typeOf(type)
        val found = try {
            universes.search(type, query.trim())
        } catch (e: Exception) {
            throw failure(HttpStatus.BAD_GATEWAY, CATALOGUE_SILENT, e.message)
        }
        return ResponseEntity.ok(found.map { ApiFoundUniverse(it.catalogue, it.entry.id, it.entry.name, it.entry.url, it.added, it.entry.description) })
    }

    override fun removeUniverse(type: String, catalogue: String, universe: String): ResponseEntity<Unit> {
        typeOf(type)
        universes.remove(type, catalogue, universe)
        return ResponseEntity.noContent().build()
    }

    override fun listCharacters(type: String, catalogue: String, universe: String): ResponseEntity<List<CharacterInfo>> =
        ResponseEntity.ok(typeOf(type).universes.entries(catalogue, universe).map { e ->
            val c = e.character
            CharacterInfo(
                character = c.id, value = UniverseFacet.valueOf(catalogue, c.id), names = c.names, url = c.url,
                classes = c.classes, included = e.included, description = c.description, choice = e.choice, localName = e.localName,
            )
        })

    override fun nameUniverseEntry(type: String, catalogue: String, entry: String, localName: LocalName): ResponseEntity<Unit> {
        typeOf(type)
        universes.setLocalName(type, catalogue, entry, localName.name)
        return ResponseEntity.noContent().build()
    }

    override fun listUniverseClasses(type: String, catalogue: String, universe: String): ResponseEntity<List<UniverseClassInfo>> =
        ResponseEntity.ok(typeOf(type).universes.classes(catalogue, universe).map {
            UniverseClassInfo(propertyClass = it.id, name = it.name, character = it.character, count = it.entries, included = it.included, choice = it.choice)
        })

    override fun chooseUniverseClass(type: String, catalogue: String, universe: String, propertyClass: String, inclusion: Inclusion): ResponseEntity<Unit> =
        chosen { universes.chooseClass(type, catalogue, universe, propertyClass, inclusion.included) }

    override fun resetUniverseClass(type: String, catalogue: String, universe: String, propertyClass: String): ResponseEntity<Unit> =
        chosen { universes.chooseClass(type, catalogue, universe, propertyClass, null) }

    override fun chooseUniverseEntry(type: String, catalogue: String, universe: String, character: String, inclusion: Inclusion): ResponseEntity<Unit> =
        chosen { universes.chooseEntry(type, catalogue, universe, character, inclusion.included) }

    override fun resetUniverseEntry(type: String, catalogue: String, universe: String, character: String): ResponseEntity<Unit> =
        chosen { universes.chooseEntry(type, catalogue, universe, character, null) }

    private fun chosen(choose: () -> Unit): ResponseEntity<Unit> {
        try {
            choose()
        } catch (e: NoSuchElementException) {
            throw failure(
                HttpStatus.NOT_FOUND,
                Label("There is no such universe in the dictionary", "Нет такой вселенной в словаре", "U rečniku nema takvog univerzuma"),
            )
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
