package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.database.source.tables.references.PHRASE_VECTOR
import io.github.vlsergey.recommend4me.database.source.tables.references.TEXT_VECTOR
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The vectors of the texts of one source's items, kept with the hash of the text and the encoder
 * that made them: a text is encoded again when it or the encoder changed, and vectors of another
 * encoder are never read. And the vectors of short texts — reviews, phrases — by the key of the
 * text, so a text is encoded once.
 */
class TextVectorRepository(private val db: DSLContext) {

    /**
     * Held while a vector is written: for milliseconds, not for an encoding. The texts of an item
     * refreshed after a page and the source's whole refresh may write the same text together, and
     * H2's MERGE of the second one meets the first one's row, which its own check did not see.
     */
    private val writing = ReentrantLock()

    /** The hash of every stored vector of [encoder]: (item, text key) to hash. */
    fun hashes(encoder: String, itemId: String? = null): Map<Pair<String, String>, String> =
        db.select(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY, TEXT_VECTOR.TEXT_HASH).from(TEXT_VECTOR)
            .where(TEXT_VECTOR.ENCODER.eq(encoder))
            .and(itemId?.let { TEXT_VECTOR.ITEM_ID.eq(it) } ?: DSL.noCondition())
            .fetch().associate { (it.value1()!! to it.value2()!!) to it.value3()!! }

    /** Every stored (item, text key), of any encoder. */
    fun keys(itemId: String? = null): Set<Pair<String, String>> =
        db.select(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY).from(TEXT_VECTOR)
            .where(itemId?.let { TEXT_VECTOR.ITEM_ID.eq(it) } ?: DSL.noCondition())
            .fetchSet { it.value1()!! to it.value2()!! }

    fun save(itemId: String, textKey: String, encoder: String, hash: String, vector: FloatArray) {
        val packed = Vectors.half(vector)
        val merge = db.insertInto(TEXT_VECTOR)
            .set(TEXT_VECTOR.ITEM_ID, itemId).set(TEXT_VECTOR.TEXT_KEY, textKey)
            .set(TEXT_VECTOR.ENCODER, encoder).set(TEXT_VECTOR.TEXT_HASH, hash).set(TEXT_VECTOR.VEC, packed)
            .onDuplicateKeyUpdate()
            .set(TEXT_VECTOR.ENCODER, encoder).set(TEXT_VECTOR.TEXT_HASH, hash).set(TEXT_VECTOR.VEC, packed)
        writing.withLock { merge.execute() }
    }

    fun delete(itemId: String, textKey: String) {
        db.deleteFrom(TEXT_VECTOR).where(TEXT_VECTOR.ITEM_ID.eq(itemId), TEXT_VECTOR.TEXT_KEY.eq(textKey)).execute()
    }

    /** Vectors of the items [ids] by [encoder]: item to text key to vector. */
    fun ofMany(ids: Collection<String>, encoder: String): Map<String, Map<String, FloatArray>> {
        val out = HashMap<String, MutableMap<String, FloatArray>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY, TEXT_VECTOR.VEC).from(TEXT_VECTOR)
                .where(TEXT_VECTOR.ITEM_ID.`in`(chunk), TEXT_VECTOR.ENCODER.eq(encoder))
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = Vectors.fromHalf(it.value3()!!) }
        }
        return out
    }

    /** Every stored vector by [encoder] of the text keys [keys], streamed: (item, text key, vector). */
    fun forEach(encoder: String, keys: Collection<String>, action: (itemId: String, key: String, vector: FloatArray) -> Unit) {
        if (keys.isEmpty()) return
        db.select(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY, TEXT_VECTOR.VEC).from(TEXT_VECTOR)
            .where(TEXT_VECTOR.ENCODER.eq(encoder), TEXT_VECTOR.TEXT_KEY.`in`(keys))
            .fetchSize(2000).fetchLazy().use { cursor ->
                cursor.forEach { action(it.value1()!!, it.value2()!!, Vectors.fromHalf(it.value3()!!)) }
            }
    }

    fun count(encoder: String): Int = db.fetchCount(TEXT_VECTOR, TEXT_VECTOR.ENCODER.eq(encoder))

    // --- Short texts ---

    /** The stored vectors by [encoder] of the texts whose keys are given. */
    fun phrases(keys: Collection<Long>, encoder: String): Map<Long, FloatArray> {
        val out = HashMap<Long, FloatArray>()
        keys.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PHRASE_VECTOR.TEXT_KEY, PHRASE_VECTOR.VEC).from(PHRASE_VECTOR)
                .where(PHRASE_VECTOR.TEXT_KEY.`in`(chunk), PHRASE_VECTOR.ENCODER.eq(encoder))
                .fetch { out[it.value1()!!] = Vectors.fromHalf(it.value2()!!) }
        }
        return out
    }

    /** Stores vectors of short texts; one of another encoder under the same key is replaced. */
    fun savePhrases(rows: List<Pair<Long, FloatArray>>, encoder: String) {
        rows.chunked(BATCH).forEach { chunk ->
            val merges = db.batch(chunk.map { (key, v) ->
                val packed = Vectors.half(v)
                db.insertInto(PHRASE_VECTOR).set(PHRASE_VECTOR.TEXT_KEY, key).set(PHRASE_VECTOR.ENCODER, encoder).set(PHRASE_VECTOR.VEC, packed)
                    .onDuplicateKeyUpdate().set(PHRASE_VECTOR.ENCODER, encoder).set(PHRASE_VECTOR.VEC, packed)
            })
            // The same phrase met by two encodings at once races as a text's vector does
            writing.withLock { merges.execute() }
        }
    }

    companion object {
        private const val BATCH = 1000
    }
}
