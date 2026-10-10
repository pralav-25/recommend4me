package io.github.vlsergey.recommend4me.mark

import io.github.vlsergey.recommend4me.database.ratings.tables.references.MATCH_FEEDBACK
import io.github.vlsergey.recommend4me.database.ratings.tables.references.PICTURE_MARK
import io.github.vlsergey.recommend4me.database.ratings.tables.references.REVIEW_MARK
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import java.time.Instant

/** What is marked: a picture (its ref is its position) or a review (its ref is its id). */
enum class MarkKind { PICTURE, REVIEW }

/** A thing of an item: the item and the thing within the kind. */
data class ThingRef(val item: ItemKey, val ref: String)

/** A picture the user marked: +1 "pick this work", −1 "drop it"; [url] the address it was marked at. */
data class PictureMark(val itemId: String, val position: Int, val url: String, val mark: Int)

data class ReviewMark(val itemId: String, val reviewId: String, val mark: Int)

/** The user's word on a match: the [match] is alike the [mark] in the sense meant (+1), or in another one (−1). */
data class MatchFeedback(val kind: MarkKind, val mark: ThingRef, val match: ThingRef, val verdict: Int)

/** The user's marks on the pictures and reviews of one source's items, and words on their matches. */
class MarkRepository(private val sourceId: String, private val db: DSLContext) {

    fun markPicture(itemId: String, position: Int, url: String, mark: Int?, now: Instant) {
        require(mark == null || mark == 1 || mark == -1) { "A mark is +1 or -1, not $mark" }
        if (mark == null) {
            db.deleteFrom(PICTURE_MARK).where(PICTURE_MARK.ITEM_ID.eq(itemId), PICTURE_MARK.POSITION.eq(position)).execute()
            return
        }
        db.insertInto(PICTURE_MARK)
            .set(PICTURE_MARK.ITEM_ID, itemId).set(PICTURE_MARK.POSITION, position).set(PICTURE_MARK.URL, url)
            .set(PICTURE_MARK.MARK, mark.toShort()).set(PICTURE_MARK.MARKED_AT, now)
            .onDuplicateKeyUpdate()
            .set(PICTURE_MARK.URL, url).set(PICTURE_MARK.MARK, mark.toShort()).set(PICTURE_MARK.MARKED_AT, now)
            .execute()
    }

    fun pictureMarks(): List<PictureMark> =
        db.selectFrom(PICTURE_MARK).orderBy(PICTURE_MARK.ITEM_ID, PICTURE_MARK.POSITION)
            .fetch { PictureMark(it.itemId!!, it.position!!, it.url!!, it.mark!!.toInt()) }

    fun pictureMarksOf(itemId: String): List<PictureMark> =
        db.selectFrom(PICTURE_MARK).where(PICTURE_MARK.ITEM_ID.eq(itemId))
            .fetch { PictureMark(it.itemId!!, it.position!!, it.url!!, it.mark!!.toInt()) }

    fun markReview(itemId: String, reviewId: String, mark: Int?, now: Instant) {
        require(mark == null || mark == 1 || mark == -1) { "A mark is +1 or -1, not $mark" }
        if (mark == null) {
            db.deleteFrom(REVIEW_MARK).where(REVIEW_MARK.ITEM_ID.eq(itemId), REVIEW_MARK.REVIEW_ID.eq(reviewId)).execute()
            return
        }
        db.insertInto(REVIEW_MARK)
            .set(REVIEW_MARK.ITEM_ID, itemId).set(REVIEW_MARK.REVIEW_ID, reviewId)
            .set(REVIEW_MARK.MARK, mark.toShort()).set(REVIEW_MARK.MARKED_AT, now)
            .onDuplicateKeyUpdate()
            .set(REVIEW_MARK.MARK, mark.toShort()).set(REVIEW_MARK.MARKED_AT, now)
            .execute()
    }

    fun reviewMarks(): List<ReviewMark> =
        db.selectFrom(REVIEW_MARK).orderBy(REVIEW_MARK.ITEM_ID, REVIEW_MARK.REVIEW_ID)
            .fetch { ReviewMark(it.itemId!!, it.reviewId!!, it.mark!!.toInt()) }

    fun reviewMarksOf(itemId: String): Map<String, Int> =
        db.select(REVIEW_MARK.REVIEW_ID, REVIEW_MARK.MARK).from(REVIEW_MARK).where(REVIEW_MARK.ITEM_ID.eq(itemId))
            .fetch().associate { it.value1()!! to it.value2()!!.toInt() }

    /** Says (+1, −1) or takes back (null) the word on a match of a mark of this source. */
    fun setFeedback(kind: MarkKind, mark: ThingRef, match: ThingRef, verdict: Int?, now: Instant) {
        require(mark.item.source == sourceId) { "The mark ${mark.item} is not of $sourceId" }
        require(verdict == null || verdict == 1 || verdict == -1) { "A verdict is +1 or -1, not $verdict" }
        val key = MATCH_FEEDBACK.MARK_KIND.eq(kind.name)
            .and(MATCH_FEEDBACK.MARK_ITEM.eq(mark.item.id)).and(MATCH_FEEDBACK.MARK_REF.eq(mark.ref))
            .and(MATCH_FEEDBACK.MATCH_SOURCE.eq(match.item.source)).and(MATCH_FEEDBACK.MATCH_ITEM.eq(match.item.id))
            .and(MATCH_FEEDBACK.MATCH_REF.eq(match.ref))
        if (verdict == null) {
            db.deleteFrom(MATCH_FEEDBACK).where(key).execute()
            return
        }
        db.insertInto(MATCH_FEEDBACK)
            .set(MATCH_FEEDBACK.MARK_KIND, kind.name).set(MATCH_FEEDBACK.MARK_ITEM, mark.item.id).set(MATCH_FEEDBACK.MARK_REF, mark.ref)
            .set(MATCH_FEEDBACK.MATCH_SOURCE, match.item.source).set(MATCH_FEEDBACK.MATCH_ITEM, match.item.id).set(MATCH_FEEDBACK.MATCH_REF, match.ref)
            .set(MATCH_FEEDBACK.VERDICT, verdict.toShort()).set(MATCH_FEEDBACK.GIVEN_AT, now)
            .onDuplicateKeyUpdate()
            .set(MATCH_FEEDBACK.VERDICT, verdict.toShort()).set(MATCH_FEEDBACK.GIVEN_AT, now)
            .execute()
    }

    /** Every word on the matches of the marks of this source of a kind. */
    fun feedback(kind: MarkKind): List<MatchFeedback> =
        db.selectFrom(MATCH_FEEDBACK).where(MATCH_FEEDBACK.MARK_KIND.eq(kind.name)).fetch {
            MatchFeedback(
                kind,
                ThingRef(ItemKey(sourceId, it.markItem!!), it.markRef!!),
                ThingRef(ItemKey(it.matchSource!!, it.matchItem!!), it.matchRef!!),
                it.verdict!!.toInt(),
            )
        }

    /**
     * The marks of the item removed from the library — of its pictures, of its reviews — and every
     * word on a match it is in: as the marked one, when it is of this source; as the match, of any.
     */
    fun forget(key: ItemKey) = db.transaction { c ->
        val tx = c.dsl()
        if (key.source == sourceId) {
            tx.deleteFrom(PICTURE_MARK).where(PICTURE_MARK.ITEM_ID.eq(key.id)).execute()
            tx.deleteFrom(REVIEW_MARK).where(REVIEW_MARK.ITEM_ID.eq(key.id)).execute()
            tx.deleteFrom(MATCH_FEEDBACK).where(MATCH_FEEDBACK.MARK_ITEM.eq(key.id)).execute()
        }
        tx.deleteFrom(MATCH_FEEDBACK).where(MATCH_FEEDBACK.MATCH_SOURCE.eq(key.source), MATCH_FEEDBACK.MATCH_ITEM.eq(key.id)).execute()
    }
}
