package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.api.CorrectionsApi
import io.github.vlsergey.recommend4me.api.model.FieldCorrection
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.item.ItemDetailsReader
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import io.github.vlsergey.recommend4me.api.model.FacetCorrection as ApiFacetCorrection

/**
 * The user's corrections of an item. Every correction is a change of the item's searchable data —
 * its texts are encoded again and it is indexed again — and of what the model reads.
 */
@RestController
class CorrectionsController(
    private val stores: Stores,
    private val details: ItemDetailsReader,
    private val recommendations: Recommendations,
    private val suggestions: FacetSuggestions,
    private val events: ApplicationEventPublisher,
) : CorrectionsApi {

    private fun corrected(store: SourceStore, item: String): ResponseEntity<ItemDetails> {
        events.publishEvent(ItemsChanged(store.id, listOf(item)))
        recommendations.scheduleRetrain(store.type)
        return details.read(ItemKey(store.id, item))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    private fun itemOf(source: String, item: String): SourceStore? =
        stores.source(source)?.takeIf { it.items.find(item) != null }

    override fun correctFacet(source: String, item: String, facetCorrection: ApiFacetCorrection): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val c = facetCorrection
        val def = store.schema.facet(c.facet) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "У источника нет поля «${c.facet}»")
        // The site's word is shown and read as it is: only the application's layer over it is corrected
        if (!def.editable) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Поле «${def.label}» — слово сайта, оно не исправляется")
        val name = c.name?.trim()?.takeIf { it.isNotEmpty() }
        val key = c.key?.trim()?.takeIf { it.isNotEmpty() }
            ?: name?.let { FacetValues.keyOf(store, c.facet, it) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Не указано значение")
        // A name is kept for a value the site does not name: one the user made up
        val known = store.items.facetNames(c.facet, listOf(key)).isNotEmpty()
        store.corrections.setFacet(item, c.facet, key, c.added, name.takeIf { !known }, Instant.now())
        // A character is the work's main one or its secondary one: said to be one, it is the other no more
        UniverseFacet.otherCharacters(c.facet)?.takeIf { c.added && key !in UniverseFacet.ORIGINAL_CHARACTERS }?.let { other ->
            if (store.corrections.facetsOf(item).any { it.facet == other && it.key == key && it.added }) store.corrections.unsetFacet(item, other, key)
        }
        // The next likeliest values of the facet are offered at once
        suggestions.currentFor(store, item, c.facet)
        return corrected(store, item)
    }

    override fun uncorrectFacet(source: String, item: String, facet: String, key: String): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        store.corrections.unsetFacet(item, facet, key)
        suggestions.currentFor(store, item, facet)
        return corrected(store, item)
    }

    override fun completeFacet(source: String, item: String, facet: String): ResponseEntity<ItemDetails> = complete(source, item, facet, true)

    override fun uncompleteFacet(source: String, item: String, facet: String): ResponseEntity<ItemDetails> = complete(source, item, facet, false)

    /** The user's word that the item's values of the [facet] are all named, or its taking back; the facet's chances made current at once. */
    private fun complete(source: String, item: String, facet: String, complete: Boolean): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val def = store.schema.facet(facet) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "У источника нет поля «$facet»")
        if (!def.editable) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Поле «${def.label}» — слово сайта, оно не исправляется")
        store.corrections.setComplete(item, facet, complete, Instant.now())
        suggestions.currentFor(store, item, facet)
        return corrected(store, item)
    }

    override fun correctField(source: String, item: String, field: String, fieldCorrection: FieldCorrection): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        if (!knownField(store, field)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "У источника нет поля «$field»")
        store.corrections.setField(item, field, fieldCorrection.content, Instant.now())
        return corrected(store, item)
    }

    override fun uncorrectField(source: String, item: String, field: String): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        store.corrections.unsetField(item, field)
        return corrected(store, item)
    }

    /** "title", "text:<a text of the source>", "number:<a number of the source>". */
    private fun knownField(store: SourceStore, field: String): Boolean = when {
        field == Corrected.TITLE -> true
        field.startsWith("text:") -> store.schema.text(field.removePrefix("text:")) != null
        field.startsWith("number:") -> store.schema.number(field.removePrefix("number:")) != null
        else -> false
    }

    override fun linkItem(source: String, item: String, itemRef: ItemRef): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val other = itemOf(itemRef.source, itemRef.item) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Такой работы нет")
        if (other.type != store.type) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Работы разного типа нельзя склеить")
        if (other.id == store.id && itemRef.item == item) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Работу нельзя склеить с ней самой")
        if (other.id == store.id) store.corrections.link(item, itemRef.item, Instant.now())
        else stores.typeOf(source).links.link(ItemKey(source, item), ItemKey(itemRef.source, itemRef.item), Instant.now())
        return corrected(store, item)
    }

    override fun unlinkItem(source: String, item: String, otherSource: String, otherItem: String): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        if (otherSource == source) store.corrections.unlink(item, otherItem)
        else stores.typeOf(source).links.unlink(ItemKey(source, item), ItemKey(otherSource, otherItem))
        return corrected(store, item)
    }
}
