package io.github.vlsergey.recommend4me.interest

import io.github.vlsergey.recommend4me.database.ratings.tables.references.INTEREST
import org.jooq.DSLContext
import java.time.Instant

/** The user's word on whether a work is interesting to read at all, and when it was said. */
data class InterestMark(val itemId: String, val interesting: Boolean, val markedAt: Instant)

/** The interest marks of the items of one source, one an item, in its ratings database. */
class InterestRepository(private val db: DSLContext) {

    fun mark(itemId: String, interesting: Boolean, now: Instant) {
        db.insertInto(INTEREST)
            .set(INTEREST.ITEM_ID, itemId).set(INTEREST.INTERESTING, interesting).set(INTEREST.MARKED_AT, now)
            .onDuplicateKeyUpdate()
            .set(INTEREST.INTERESTING, interesting).set(INTEREST.MARKED_AT, now)
            .execute()
    }

    fun ofItem(itemId: String): InterestMark? =
        db.selectFrom(INTEREST).where(INTEREST.ITEM_ID.eq(itemId)).fetchOne { InterestMark(it.itemId!!, it.interesting!!, it.markedAt!!) }

    /** Every mark of the source: item to mark. */
    fun all(): Map<String, InterestMark> =
        db.selectFrom(INTEREST).fetch().associate { it.itemId!! to InterestMark(it.itemId!!, it.interesting!!, it.markedAt!!) }

    /** The mark taken back; also of the item removed from the library. */
    fun forget(itemId: String) {
        db.deleteFrom(INTEREST).where(INTEREST.ITEM_ID.eq(itemId)).execute()
    }
}
