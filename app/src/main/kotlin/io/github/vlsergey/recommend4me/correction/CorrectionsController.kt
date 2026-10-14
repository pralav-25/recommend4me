package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.api.CorrectionsApi
import io.github.vlsergey.recommend4me.api.model.FacetAnswer
import io.github.vlsergey.recommend4me.api.model.FieldCorrection
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemDetailsReader
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.interest.LinkedInterest
import io.github.vlsergey.recommend4me.rating.LinkedGrades
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import io.github.vlsergey.recommend4me.api.model.FacetCorrection as ApiFacetCorrection

private val log = LoggerFactory.getLogger(CorrectionsController::class.java)

private fun noField(store: SourceStore, field: String) =
    Refusal.NO_FIELD.refuse(HttpStatus.BAD_REQUEST, "The source ${store.id} has no field '$field'", "field" to field)

/** The site's word is shown and read as it is: only the application's layer over it is corrected. */
private fun siteField(store: SourceStore, def: FacetDef) = Refusal.SITE_FIELD.refuse(
    HttpStatus.BAD_REQUEST, "The field '${def.key}' of ${store.id} is the site's word, it is not corrected", "field" to I18nKeys.facetLabel(store.id, def),
)

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
    private val linkedGrades: LinkedGrades,
    private val linkedInterest: LinkedInterest,
) : CorrectionsApi {

    private fun corrected(store: SourceStore, item: String): ResponseEntity<ItemDetails> {
        events.publishEvent(ItemsChanged(store.id, listOf(item)))
        recommendations.scheduleRetrain(store.type)
        // Not waiting for the chances the change makes: the page asks for them apart
        return details.read(ItemKey(store.id, item), withChances = false)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    /**
     * An answer on a facet: [change] written, and nothing else — nothing of the work read back: a
     * second answer right after the first waits for no reading. The page shows the answer once it
     * is kept and reads the work anew once the answers have settled; the chances it changes are
     * made apart ([FacetSuggestions.answered]), and the page asks for them when they are made
     * (settleSuggestions).
     */
    private fun answered(store: SourceStore, item: String, facet: String, change: () -> Unit) {
        val started = System.currentTimeMillis()
        change()
        suggestions.answered(store, item, facet)
        // The search and the model of the grades read the user's answers: the work is indexed and learnt anew
        events.publishEvent(FacetAnswered(store.type, store.id, item, facet))
        log.info("{}: an answer on {} of {} written in {} ms", store.id, facet, item, System.currentTimeMillis() - started)
    }

    private fun itemOf(source: String, item: String): SourceStore? =
        stores.source(source)?.takeIf { it.items.find(item) != null }

    override fun correctFacet(source: String, item: String, facetCorrection: ApiFacetCorrection): ResponseEntity<FacetAnswer> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val c = facetCorrection
        val def = store.schema.facet(c.facet) ?: throw noField(store, c.facet)
        // The site's word is shown and read as it is: only the application's layer over it is corrected
        if (!def.editable) throw siteField(store, def)
        val name = c.name?.trim()?.takeIf { it.isNotEmpty() }
        val key = c.key?.trim()?.takeIf { it.isNotEmpty() }
            ?: name?.let { FacetValues.keyOf(store, c.facet, it) }
            ?: throw Refusal.NO_VALUE.refuse(HttpStatus.BAD_REQUEST, "Neither a key nor a name of a value of '${c.facet}' given")
        // A tag the work has by a tag under it the user confirmed is not said to be not the work's: the answer on that one is
        if (!c.added) store.hierarchies()[c.facet]?.let { hierarchy ->
            val under = hierarchy.descendants(key)
            store.corrections.facetsOf(item).firstOrNull { it.facet == c.facet && it.added && it.key in under }?.let { held ->
                throw Refusal.TAG_IMPLIED.refuse(
                    HttpStatus.CONFLICT, "$key is above ${held.key}, which the user confirmed of $item",
                    "tag" to (store.tags.shown(listOf(held.key))[held.key] ?: held.key),
                )
            }
        }
        answered(store, item, c.facet) {
            // A name is kept for a value the site does not name: one the user made up
            val known = store.names(c.facet, listOf(key)).isNotEmpty()
            store.corrections.setFacet(item, c.facet, key, c.added, name.takeIf { !known }, Instant.now())
            // A character is the work's main one or its secondary one: said to be one, it is the other no more
            UniverseFacet.otherCharacters(c.facet)?.takeIf { c.added && key !in UniverseFacet.ORIGINAL_CHARACTERS }?.let { other ->
                if (store.corrections.facetsOf(item).any { it.facet == other && it.key == key && it.added }) store.corrections.unsetFacet(item, other, key)
            }
        }
        return ResponseEntity.ok(FacetAnswer(c.facet, key, name))
    }

    override fun uncorrectFacet(source: String, item: String, facet: String, key: String): ResponseEntity<Unit> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        answered(store, item, facet) { store.corrections.unsetFacet(item, facet, key) }
        return ResponseEntity.noContent().build()
    }

    override fun completeFacet(source: String, item: String, facet: String): ResponseEntity<Unit> = complete(source, item, facet, true)

    override fun uncompleteFacet(source: String, item: String, facet: String): ResponseEntity<Unit> = complete(source, item, facet, false)

    /** The user's word that the item's values of the [facet] are all named, or its taking back; the facet's chances made current apart. */
    private fun complete(source: String, item: String, facet: String, complete: Boolean): ResponseEntity<Unit> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val def = store.schema.facet(facet) ?: throw noField(store, facet)
        if (!def.editable) throw siteField(store, def)
        answered(store, item, facet) { store.corrections.setComplete(item, facet, complete, Instant.now()) }
        return ResponseEntity.noContent().build()
    }

    override fun correctField(source: String, item: String, field: String, fieldCorrection: FieldCorrection): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        if (!knownField(store, field)) throw noField(store, field)
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
        val other = itemOf(itemRef.source, itemRef.item)
            ?: throw Refusal.NO_SUCH_WORK.refuse(HttpStatus.BAD_REQUEST, "There is no work ${itemRef.source}/${itemRef.item}")
        if (other.type != store.type) {
            throw Refusal.JOIN_TYPES.refuse(HttpStatus.BAD_REQUEST, "A work of ${store.type} is not joined with one of ${other.type}")
        }
        if (other.id == store.id && itemRef.item == item) {
            throw Refusal.JOIN_SELF.refuse(HttpStatus.BAD_REQUEST, "The work $source/$item is not joined with itself")
        }
        if (other.id == store.id) store.corrections.link(item, itemRef.item, Instant.now())
        else stores.typeOf(source).links.link(ItemKey(source, item), ItemKey(itemRef.source, itemRef.item), Instant.now())
        // One work, one grade and one interest mark: written to each of its items; a link taken back leaves them them
        linkedGrades.share(ItemKey(source, item))
        linkedInterest.share(ItemKey(source, item))
        return corrected(store, item)
    }

    override fun unlinkItem(source: String, item: String, otherSource: String, otherItem: String): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        if (otherSource == source) store.corrections.unlink(item, otherItem)
        else stores.typeOf(source).links.unlink(ItemKey(source, item), ItemKey(otherSource, otherItem))
        return corrected(store, item)
    }
}
