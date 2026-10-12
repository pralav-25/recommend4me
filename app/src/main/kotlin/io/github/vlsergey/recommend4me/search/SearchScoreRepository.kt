package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.database.model.tables.references.SEARCH_SCORE
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext

/** A score of the reranker for a line and a work, and the hash of the text of the work it read. */
class StoredScore(val textsHash: Long, val score: Float)

/**
 * What the reranker said of the works found for a line, in the model database of the content type:
 * read once, whatever page of the search asks for them, however often the line is searched again.
 */
class SearchScoreRepository(private val db: DSLContext) {

    /** The scores of the line by the [reranker], of every work read for it. */
    fun of(reranker: String, query: String): Map<ItemKey, StoredScore> =
        db.select(SEARCH_SCORE.SOURCE, SEARCH_SCORE.ITEM_ID, SEARCH_SCORE.TEXTS_HASH, SEARCH_SCORE.SCORE).from(SEARCH_SCORE)
            .where(SEARCH_SCORE.RERANKER.eq(reranker), SEARCH_SCORE.QUERY.eq(query))
            .fetch().associate { ItemKey(it.value1()!!, it.value2()!!) to StoredScore(it.value3()!!, it.value4()!!) }

    /** The scores of works just read for the line, in one transaction. */
    fun save(reranker: String, query: String, scores: Map<ItemKey, StoredScore>) {
        if (scores.isEmpty()) return
        db.transaction { c ->
            val tx = c.dsl()
            tx.batch(scores.map { (key, s) ->
                tx.insertInto(SEARCH_SCORE)
                    .set(SEARCH_SCORE.RERANKER, reranker).set(SEARCH_SCORE.QUERY, query)
                    .set(SEARCH_SCORE.SOURCE, key.source).set(SEARCH_SCORE.ITEM_ID, key.id)
                    .set(SEARCH_SCORE.TEXTS_HASH, s.textsHash).set(SEARCH_SCORE.SCORE, s.score)
                    .onDuplicateKeyUpdate().set(SEARCH_SCORE.TEXTS_HASH, s.textsHash).set(SEARCH_SCORE.SCORE, s.score)
            }).execute()
        }
    }

    /** Every score of another reranker than [reranker]: its numbers are of another model. */
    fun keepOnly(reranker: String) {
        db.deleteFrom(SEARCH_SCORE).where(SEARCH_SCORE.RERANKER.ne(reranker)).execute()
    }

    /** The scores of a work removed from the library. */
    fun forget(key: ItemKey) {
        db.deleteFrom(SEARCH_SCORE).where(SEARCH_SCORE.SOURCE.eq(key.source), SEARCH_SCORE.ITEM_ID.eq(key.id)).execute()
    }
}
