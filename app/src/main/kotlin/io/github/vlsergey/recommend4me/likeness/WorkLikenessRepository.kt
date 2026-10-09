package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.database.model.tables.references.WORK_LIKENESS
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A reranker's score of a work's description against a graded work's, and the hash of both texts it was read from. */
class PairScore(val graded: ItemKey, val item: ItemKey, val textsHash: Long, val score: Float)

/** The rerankers' scores of the works of a content type against its graded works, kept in its model database. */
class WorkLikenessRepository(private val db: DSLContext) {
    /** One writer at a time: a pair written twice at once would break the key. */
    private val writing = ReentrantLock()

    /** Every score of the [reader]: graded work, then work, then the score. */
    fun forEach(reader: String, action: (graded: ItemKey, item: ItemKey, score: Float) -> Unit) {
        db.select(WORK_LIKENESS.GRADED_SOURCE, WORK_LIKENESS.GRADED_ID, WORK_LIKENESS.SOURCE, WORK_LIKENESS.ITEM_ID, WORK_LIKENESS.SCORE)
            .from(WORK_LIKENESS).where(WORK_LIKENESS.READER.eq(reader))
            .fetchSize(10_000).fetchLazy().use { cursor ->
                cursor.forEach { action(ItemKey(it.value1()!!, it.value2()!!), ItemKey(it.value3()!!, it.value4()!!), it.value5()!!) }
            }
    }

    /** The hash of the texts every pair of the [reader] was read from: what is read again, and what is not. */
    fun hashes(reader: String): Map<Pair<ItemKey, ItemKey>, Long> {
        val out = HashMap<Pair<ItemKey, ItemKey>, Long>()
        db.select(WORK_LIKENESS.GRADED_SOURCE, WORK_LIKENESS.GRADED_ID, WORK_LIKENESS.SOURCE, WORK_LIKENESS.ITEM_ID, WORK_LIKENESS.TEXTS_HASH)
            .from(WORK_LIKENESS).where(WORK_LIKENESS.READER.eq(reader))
            .fetchSize(10_000).fetchLazy().use { cursor ->
                cursor.forEach { out[ItemKey(it.value1()!!, it.value2()!!) to ItemKey(it.value3()!!, it.value4()!!)] = it.value5()!! }
            }
        return out
    }

    /** The scores of one work against every graded work. */
    fun ofItem(reader: String, item: ItemKey): Map<ItemKey, Float> =
        db.select(WORK_LIKENESS.GRADED_SOURCE, WORK_LIKENESS.GRADED_ID, WORK_LIKENESS.SCORE).from(WORK_LIKENESS)
            .where(WORK_LIKENESS.READER.eq(reader), WORK_LIKENESS.SOURCE.eq(item.source), WORK_LIKENESS.ITEM_ID.eq(item.id))
            .fetch().associate { ItemKey(it.value1()!!, it.value2()!!) to it.value3()!! }

    /** Writes [scores] of the [reader], replacing those of the same pairs, in one transaction. */
    fun save(reader: String, scores: List<PairScore>) = writing.withLock {
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            scores.chunked(BATCH).forEach { chunk ->
                chunk.forEach { s ->
                    tx.deleteFrom(WORK_LIKENESS).where(
                        WORK_LIKENESS.READER.eq(reader),
                        WORK_LIKENESS.GRADED_SOURCE.eq(s.graded.source), WORK_LIKENESS.GRADED_ID.eq(s.graded.id),
                        WORK_LIKENESS.SOURCE.eq(s.item.source), WORK_LIKENESS.ITEM_ID.eq(s.item.id),
                    ).execute()
                }
                val batch = tx.batch(
                    tx.insertInto(
                        WORK_LIKENESS, WORK_LIKENESS.READER, WORK_LIKENESS.GRADED_SOURCE, WORK_LIKENESS.GRADED_ID,
                        WORK_LIKENESS.SOURCE, WORK_LIKENESS.ITEM_ID, WORK_LIKENESS.TEXTS_HASH, WORK_LIKENESS.SCORE,
                    ).values(null as String?, null as String?, null as String?, null as String?, null as String?, null as Long?, null as Float?),
                )
                chunk.forEach { s -> batch.bind(reader, s.graded.source, s.graded.id, s.item.source, s.item.id, s.textsHash, s.score) }
                batch.execute()
            }
        }
    }

    companion object {
        private const val BATCH = 1000
    }
}
