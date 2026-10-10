package io.github.vlsergey.recommend4me.rating

import io.github.vlsergey.recommend4me.database.ratings.tables.records.RatingRecord
import io.github.vlsergey.recommend4me.database.ratings.tables.references.RATING
import org.jooq.DSLContext
import java.time.Instant

data class Rating(val itemId: String, val version: String, val grade: Int, val ratedAt: Instant)

/** The user's grades of the items of one source, one per version. */
class RatingRepository(private val db: DSLContext) {

    fun rate(itemId: String, version: String, grade: Int, now: Instant) {
        require(grade in Grades.RANGE) { "A grade is ${Grades.MIN}..${Grades.MAX}, not $grade" }
        db.insertInto(RATING)
            .set(RATING.ITEM_ID, itemId).set(RATING.VERSION, version).set(RATING.GRADE, grade).set(RATING.RATED_AT, now)
            .onDuplicateKeyUpdate()
            .set(RATING.GRADE, grade).set(RATING.RATED_AT, now)
            .execute()
    }

    fun unrate(itemId: String, version: String) {
        db.deleteFrom(RATING).where(RATING.ITEM_ID.eq(itemId), RATING.VERSION.eq(version)).execute()
    }

    fun ofItem(itemId: String): List<Rating> =
        db.selectFrom(RATING).where(RATING.ITEM_ID.eq(itemId)).orderBy(RATING.RATED_AT.desc()).fetch(::map)

    fun all(): List<Rating> =
        db.selectFrom(RATING).orderBy(RATING.ITEM_ID, RATING.RATED_AT).fetch(::map)

    fun gradedItems(): Set<String> = db.selectDistinct(RATING.ITEM_ID).from(RATING).fetchSet { it.value1()!! }

    fun count(): Int = db.fetchCount(RATING)

    /** Every grade of the item removed from the library, of every version of it. */
    fun forget(itemId: String) {
        db.deleteFrom(RATING).where(RATING.ITEM_ID.eq(itemId)).execute()
    }

    private fun map(r: RatingRecord) = Rating(r.itemId!!, r.version!!, r.grade!!, r.ratedAt!!)
}
