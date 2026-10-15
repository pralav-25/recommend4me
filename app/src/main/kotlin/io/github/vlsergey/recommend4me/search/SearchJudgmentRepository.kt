package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.database.preferences.tables.references.SEARCH_JUDGMENT
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import java.time.Instant

/**
 * The user's word on what a search found, of one content type: a work answers a line searched for,
 * or does not. The line as it is searched — trimmed, in lower case — so one line is one query
 * however it was typed. Kept in the user's preferences, the type's by the sources of its works.
 */
class SearchJudgmentRepository(
    private val db: DSLContext,
    /** The sources of the type. */
    private val sources: Collection<String>,
) {

    /** The word on the work for the line; null takes it back. */
    fun judge(query: String, key: ItemKey, relevant: Boolean?, now: Instant) {
        val q = normal(query)
        if (relevant == null) {
            db.deleteFrom(SEARCH_JUDGMENT)
                .where(SEARCH_JUDGMENT.QUERY.eq(q), SEARCH_JUDGMENT.SOURCE.eq(key.source), SEARCH_JUDGMENT.ITEM_ID.eq(key.id)).execute()
            return
        }
        db.insertInto(SEARCH_JUDGMENT)
            .set(SEARCH_JUDGMENT.QUERY, q).set(SEARCH_JUDGMENT.SOURCE, key.source).set(SEARCH_JUDGMENT.ITEM_ID, key.id)
            .set(SEARCH_JUDGMENT.RELEVANT, relevant).set(SEARCH_JUDGMENT.JUDGED_AT, now)
            .onDuplicateKeyUpdate().set(SEARCH_JUDGMENT.RELEVANT, relevant).set(SEARCH_JUDGMENT.JUDGED_AT, now)
            .execute()
    }

    /** The word on every work judged for the line: work to whether it answers it. */
    fun of(query: String): Map<ItemKey, Boolean> =
        db.select(SEARCH_JUDGMENT.SOURCE, SEARCH_JUDGMENT.ITEM_ID, SEARCH_JUDGMENT.RELEVANT).from(SEARCH_JUDGMENT)
            .where(SEARCH_JUDGMENT.QUERY.eq(normal(query)), SEARCH_JUDGMENT.SOURCE.`in`(sources))
            .fetch().associate { ItemKey(it.value1()!!, it.value2()!!) to it.value3()!! }

    /** Every line judged, with the word on each work judged for it. */
    fun all(): Map<String, Map<ItemKey, Boolean>> =
        db.select(SEARCH_JUDGMENT.QUERY, SEARCH_JUDGMENT.SOURCE, SEARCH_JUDGMENT.ITEM_ID, SEARCH_JUDGMENT.RELEVANT).from(SEARCH_JUDGMENT)
            .where(SEARCH_JUDGMENT.SOURCE.`in`(sources))
            .fetch().groupBy({ it.value1()!! }, { ItemKey(it.value2()!!, it.value3()!!) to it.value4()!! }).mapValues { it.value.toMap() }

    /** The judgments of a work removed from the library. */
    fun forget(key: ItemKey) {
        db.deleteFrom(SEARCH_JUDGMENT).where(SEARCH_JUDGMENT.SOURCE.eq(key.source), SEARCH_JUDGMENT.ITEM_ID.eq(key.id)).execute()
    }

    companion object {
        /** A line as one query: trimmed, in lower case — as the search reads it. */
        fun normal(query: String) = query.trim().lowercase()
    }
}
