package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.database.model.tables.references.WORK_LIKENESS
import io.github.vlsergey.recommend4me.database.model.tables.references.WORK_LIKENESS_TEXT
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A reranker's score of a work's description against a graded work's, and the hash of both texts it was read from. */
class PairScore(val graded: ItemKey, val item: ItemKey, val textsHash: Long, val score: Float)

/** A work's text as a reranker read every pair of it: the hash of the text, and whether the work was a graded one. */
data class WorkText(val hash: Long, val graded: Boolean)

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

    /** Every work's text as the [reader] last read all its pairs: what a pass reads again, and what it does not. */
    fun texts(reader: String): Map<ItemKey, WorkText> =
        db.select(WORK_LIKENESS_TEXT.SOURCE, WORK_LIKENESS_TEXT.ITEM_ID, WORK_LIKENESS_TEXT.TEXT_HASH, WORK_LIKENESS_TEXT.GRADED)
            .from(WORK_LIKENESS_TEXT).where(WORK_LIKENESS_TEXT.READER.eq(reader))
            .fetch().associate { ItemKey(it.value1()!!, it.value2()!!) to WorkText(it.value3()!!, it.value4()!!) }

    /** The hash of the texts of every pair of the [reader] with [graded] as the graded work: the work read against it, then the hash. */
    fun hashesOfGraded(reader: String, graded: ItemKey): Map<ItemKey, Long> =
        db.select(WORK_LIKENESS.SOURCE, WORK_LIKENESS.ITEM_ID, WORK_LIKENESS.TEXTS_HASH).from(WORK_LIKENESS)
            .where(WORK_LIKENESS.READER.eq(reader), WORK_LIKENESS.GRADED_SOURCE.eq(graded.source), WORK_LIKENESS.GRADED_ID.eq(graded.id))
            .fetch().associate { ItemKey(it.value1()!!, it.value2()!!) to it.value3()!! }

    /** The hash of the texts of every pair of the [reader] with [item] as the work read: the graded work, then the hash. */
    fun hashesOfItem(reader: String, item: ItemKey): Map<ItemKey, Long> =
        db.select(WORK_LIKENESS.GRADED_SOURCE, WORK_LIKENESS.GRADED_ID, WORK_LIKENESS.TEXTS_HASH).from(WORK_LIKENESS)
            .where(WORK_LIKENESS.READER.eq(reader), WORK_LIKENESS.SOURCE.eq(item.source), WORK_LIKENESS.ITEM_ID.eq(item.id))
            .fetch().associate { ItemKey(it.value1()!!, it.value2()!!) to it.value3()!! }

    /** Gives the [pairs] of the [reader] their hashes of [PairScore.textsHash], their scores as they are. */
    fun restamp(reader: String, pairs: List<PairScore>) = writing.withLock {
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            pairs.chunked(BATCH).forEach { chunk ->
                val batch = tx.batch(
                    tx.update(WORK_LIKENESS).set(WORK_LIKENESS.TEXTS_HASH, null as Long?).where(
                        WORK_LIKENESS.READER.eq(null as String?),
                        WORK_LIKENESS.GRADED_SOURCE.eq(null as String?), WORK_LIKENESS.GRADED_ID.eq(null as String?),
                        WORK_LIKENESS.SOURCE.eq(null as String?), WORK_LIKENESS.ITEM_ID.eq(null as String?),
                    ),
                )
                chunk.forEach { p -> batch.bind(p.textsHash, reader, p.graded.source, p.graded.id, p.item.source, p.item.id) }
                batch.execute()
            }
        }
    }

    /** Keeps the [texts] of the works the [reader] has read every pair of, and forgets those of the works [gone] from the pass. */
    fun keepTexts(reader: String, texts: Map<ItemKey, WorkText>, gone: Collection<ItemKey>) = writing.withLock {
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            gone.forEach { key ->
                tx.deleteFrom(WORK_LIKENESS_TEXT)
                    .where(WORK_LIKENESS_TEXT.READER.eq(reader), WORK_LIKENESS_TEXT.SOURCE.eq(key.source), WORK_LIKENESS_TEXT.ITEM_ID.eq(key.id))
                    .execute()
            }
            texts.entries.chunked(BATCH).forEach { chunk ->
                val batch = tx.batch(
                    tx.mergeInto(WORK_LIKENESS_TEXT, WORK_LIKENESS_TEXT.READER, WORK_LIKENESS_TEXT.SOURCE, WORK_LIKENESS_TEXT.ITEM_ID, WORK_LIKENESS_TEXT.TEXT_HASH, WORK_LIKENESS_TEXT.GRADED)
                        .key(WORK_LIKENESS_TEXT.READER, WORK_LIKENESS_TEXT.SOURCE, WORK_LIKENESS_TEXT.ITEM_ID)
                        .values(null as String?, null as String?, null as String?, null as Long?, null as Boolean?),
                )
                chunk.forEach { (key, text) -> batch.bind(reader, key.source, key.id, text.hash, text.graded) }
                batch.execute()
            }
        }
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

    /** Every likeness the item removed from the library is in, by every reader: as the graded work and as the one read against it. */
    fun forget(key: ItemKey) = writing.withLock {
        // Each side in a statement of its own, by an index of its own — the key, of the reader first,
        // and the one of the work read — where one statement of both sides at once reads the whole
        // table. The readers: those of the works' texts, every reader that has finished a pass
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.selectDistinct(WORK_LIKENESS_TEXT.READER).from(WORK_LIKENESS_TEXT).fetch(WORK_LIKENESS_TEXT.READER).filterNotNull().forEach { reader ->
                tx.deleteFrom(WORK_LIKENESS)
                    .where(WORK_LIKENESS.READER.eq(reader), WORK_LIKENESS.GRADED_SOURCE.eq(key.source), WORK_LIKENESS.GRADED_ID.eq(key.id))
                    .execute()
            }
            tx.deleteFrom(WORK_LIKENESS).where(WORK_LIKENESS.SOURCE.eq(key.source), WORK_LIKENESS.ITEM_ID.eq(key.id)).execute()
            tx.deleteFrom(WORK_LIKENESS_TEXT).where(WORK_LIKENESS_TEXT.SOURCE.eq(key.source), WORK_LIKENESS_TEXT.ITEM_ID.eq(key.id)).execute()
        }
    }

    companion object {
        private const val BATCH = 1000
    }
}
