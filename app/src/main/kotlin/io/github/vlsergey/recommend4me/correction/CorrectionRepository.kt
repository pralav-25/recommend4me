package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.corrections.tables.references.FACET_COMPLETE
import io.github.vlsergey.recommend4me.database.corrections.tables.references.FACET_CORRECTION
import io.github.vlsergey.recommend4me.database.corrections.tables.references.FIELD_CORRECTION
import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_LINK
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** A value of a facet the user added to an item or took from it. */
data class FacetCorrection(val itemId: String, val facet: String, val key: String, val added: Boolean, val name: String?)

/**
 * The user's corrections of one source's items: facet values added and taken away, facets whose
 * values are all named, fields overridden, and items of the source that are one work.
 */
class CorrectionRepository(private val db: DSLContext) {

    fun setFacet(itemId: String, facet: String, key: String, added: Boolean, name: String?, now: Instant) {
        db.insertInto(FACET_CORRECTION)
            .set(FACET_CORRECTION.ITEM_ID, itemId).set(FACET_CORRECTION.FACET, facet).set(FACET_CORRECTION.VALUE_KEY, key.take(500))
            .set(FACET_CORRECTION.ADDED, added).set(FACET_CORRECTION.NAME, name?.take(1000)).set(FACET_CORRECTION.CORRECTED_AT, now)
            .onDuplicateKeyUpdate()
            .set(FACET_CORRECTION.ADDED, added).set(FACET_CORRECTION.NAME, name?.take(1000)).set(FACET_CORRECTION.CORRECTED_AT, now)
            .execute()
    }

    fun unsetFacet(itemId: String, facet: String, key: String) {
        db.deleteFrom(FACET_CORRECTION)
            .where(FACET_CORRECTION.ITEM_ID.eq(itemId), FACET_CORRECTION.FACET.eq(facet), FACET_CORRECTION.VALUE_KEY.eq(key))
            .execute()
    }

    /** Takes every correction of the value away, from every item; returns the items that had one. */
    fun removeFacetValue(facet: String, key: String): List<String> {
        val items = db.select(FACET_CORRECTION.ITEM_ID).from(FACET_CORRECTION)
            .where(FACET_CORRECTION.FACET.eq(facet), FACET_CORRECTION.VALUE_KEY.eq(key)).fetch { it.value1()!! }
        db.deleteFrom(FACET_CORRECTION).where(FACET_CORRECTION.FACET.eq(facet), FACET_CORRECTION.VALUE_KEY.eq(key)).execute()
        return items
    }

    fun facetsOf(itemId: String): List<FacetCorrection> = facetsOf(listOf(itemId))[itemId].orEmpty()

    fun facetsOf(ids: Collection<String>): Map<String, List<FacetCorrection>> {
        val out = HashMap<String, MutableList<FacetCorrection>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.selectFrom(FACET_CORRECTION).where(FACET_CORRECTION.ITEM_ID.`in`(chunk))
                .fetch { out.getOrPut(it.itemId!!) { ArrayList() } += FacetCorrection(it.itemId!!, it.facet!!, it.valueKey!!, it.added!!, it.name) }
        }
        return out
    }

    /** Every facet correction: they are few, the user makes them by hand. */
    fun allFacets(): List<FacetCorrection> =
        db.selectFrom(FACET_CORRECTION).fetch { FacetCorrection(it.itemId!!, it.facet!!, it.valueKey!!, it.added!!, it.name) }

    fun setField(itemId: String, field: String, content: String, now: Instant) {
        db.insertInto(FIELD_CORRECTION)
            .set(FIELD_CORRECTION.ITEM_ID, itemId).set(FIELD_CORRECTION.FIELD, field).set(FIELD_CORRECTION.CONTENT, content)
            .set(FIELD_CORRECTION.CORRECTED_AT, now)
            .onDuplicateKeyUpdate()
            .set(FIELD_CORRECTION.CONTENT, content).set(FIELD_CORRECTION.CORRECTED_AT, now)
            .execute()
    }

    fun unsetField(itemId: String, field: String) {
        db.deleteFrom(FIELD_CORRECTION).where(FIELD_CORRECTION.ITEM_ID.eq(itemId), FIELD_CORRECTION.FIELD.eq(field)).execute()
    }

    fun fieldsOf(itemId: String): Map<String, String> = fieldsOf(listOf(itemId))[itemId].orEmpty()

    fun fieldsOf(ids: Collection<String>): Map<String, Map<String, String>> {
        val out = HashMap<String, MutableMap<String, String>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(FIELD_CORRECTION.ITEM_ID, FIELD_CORRECTION.FIELD, FIELD_CORRECTION.CONTENT).from(FIELD_CORRECTION)
                .where(FIELD_CORRECTION.ITEM_ID.`in`(chunk))
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = it.value3()!! }
        }
        return out
    }

    /** Every field correction: item to field to content. */
    fun allFields(): Map<String, Map<String, String>> =
        db.select(FIELD_CORRECTION.ITEM_ID, FIELD_CORRECTION.FIELD, FIELD_CORRECTION.CONTENT).from(FIELD_CORRECTION).fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! }).mapValues { it.value.toMap() }

    // --- Facets whose values are all named ---

    /** The user's word that the item's values of the facet are all named ([complete]), or its taking back. */
    fun setComplete(itemId: String, facet: String, complete: Boolean, now: Instant) {
        if (complete) {
            db.insertInto(FACET_COMPLETE).set(FACET_COMPLETE.ITEM_ID, itemId).set(FACET_COMPLETE.FACET, facet).set(FACET_COMPLETE.COMPLETED_AT, now)
                .onDuplicateKeyIgnore().execute()
        } else {
            db.deleteFrom(FACET_COMPLETE).where(FACET_COMPLETE.ITEM_ID.eq(itemId), FACET_COMPLETE.FACET.eq(facet)).execute()
        }
    }

    /** The facets whose values the user said are all named, of each of the items [ids] that has any. */
    fun completeOf(ids: Collection<String>): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(FACET_COMPLETE.ITEM_ID, FACET_COMPLETE.FACET).from(FACET_COMPLETE).where(FACET_COMPLETE.ITEM_ID.`in`(chunk))
                .fetch { out.getOrPut(it.value1()!!) { HashSet() } += it.value2()!! }
        }
        return out
    }

    fun completeOf(itemId: String): Set<String> = completeOf(listOf(itemId))[itemId].orEmpty()

    /** Every item with a facet whose values are all named: they are few, the user says it by hand. */
    fun allComplete(): Map<String, Set<String>> =
        db.select(FACET_COMPLETE.ITEM_ID, FACET_COMPLETE.FACET).from(FACET_COMPLETE).fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! }).mapValues { it.value.toSet() }

    // --- Links within the source ---

    fun link(a: String, b: String, now: Instant) {
        val (first, second) = if (a < b) a to b else b to a
        db.insertInto(ITEM_LINK).set(ITEM_LINK.ITEM_ID, first).set(ITEM_LINK.OTHER_ITEM_ID, second).set(ITEM_LINK.LINKED_AT, now)
            .onDuplicateKeyIgnore().execute()
    }

    fun unlink(a: String, b: String) {
        db.deleteFrom(ITEM_LINK).where(
            DSL.or(
                ITEM_LINK.ITEM_ID.eq(a).and(ITEM_LINK.OTHER_ITEM_ID.eq(b)),
                ITEM_LINK.ITEM_ID.eq(b).and(ITEM_LINK.OTHER_ITEM_ID.eq(a)),
            ),
        ).execute()
    }

    fun links(): List<Pair<String, String>> =
        db.select(ITEM_LINK.ITEM_ID, ITEM_LINK.OTHER_ITEM_ID).from(ITEM_LINK).fetch { it.value1()!! to it.value2()!! }

    /** Every answer of the user on the item removed from the library, and its links to the source's other items. */
    fun forget(itemId: String) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(FACET_CORRECTION).where(FACET_CORRECTION.ITEM_ID.eq(itemId)).execute()
        tx.deleteFrom(FIELD_CORRECTION).where(FIELD_CORRECTION.ITEM_ID.eq(itemId)).execute()
        tx.deleteFrom(FACET_COMPLETE).where(FACET_COMPLETE.ITEM_ID.eq(itemId)).execute()
        tx.deleteFrom(ITEM_LINK).where(ITEM_LINK.ITEM_ID.eq(itemId).or(ITEM_LINK.OTHER_ITEM_ID.eq(itemId))).execute()
    }

    companion object {
        private const val BATCH = 1000
    }
}
