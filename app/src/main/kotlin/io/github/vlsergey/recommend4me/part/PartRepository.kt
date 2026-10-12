package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import io.github.vlsergey.recommend4me.database.source.tables.references.PART_VECTOR
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

data class StoredPart(
    val partId: String,
    val position: Int,
    val title: String?,
    val hasText: Boolean,
    val publishedAt: Instant?,
)

/** A part whose text is to be encoded: its text and the hash it will be kept with. */
class PartToEncode(val itemId: String, val partId: String, val content: String, val hash: String)

/**
 * The parts of the works' texts — chapters — of one source, and the vector of every window of
 * every part: a long text is a set of vectors rather than their average.
 */
class PartRepository(private val db: DSLContext) {

    /**
     * Stores parts, a known one updated; a part named without its text keeps the text it had, and a
     * part whose text changed is encoded again.
     */
    fun save(itemId: String, parts: List<PartData>) {
        parts.forEach { p ->
            val id = p.id.take(100)
            val known = db.select(PART.CONTENT).from(PART).where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(id)).fetchOne()
            if (known == null) {
                db.insertInto(PART)
                    .set(PART.ITEM_ID, itemId).set(PART.PART_ID, id)
                    // A part whose place the page does not tell goes after the known ones
                    .set(PART.POSITION, if (p.position >= 0) p.position else UNKNOWN_POSITION).set(PART.TITLE, p.title?.take(1000))
                    .set(PART.CONTENT, p.content).set(PART.PUBLISHED_AT, p.publishedAt)
                    .execute()
                return@forEach
            }
            var update = db.update(PART).set(PART.ITEM_ID, itemId)
            if (p.position >= 0) update = update.set(PART.POSITION, p.position)
            p.title?.let { update = update.set(PART.TITLE, it.take(1000)) }
            if (p.publishedAt != null) update = update.set(PART.PUBLISHED_AT, p.publishedAt)
            if (p.content != null && p.content != known.value1()) {
                update = update.set(PART.CONTENT, p.content).setNull(PART.ENCODER).setNull(PART.ENCODED_HASH)
            }
            update.where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(id)).execute()
        }
    }

    fun ofItem(itemId: String): List<StoredPart> =
        db.select(PART.PART_ID, PART.POSITION, PART.TITLE, PART.CONTENT.isNotNull, PART.PUBLISHED_AT).from(PART)
            .where(PART.ITEM_ID.eq(itemId)).orderBy(PART.POSITION)
            .fetch { StoredPart(it.value1()!!, it.value2()!!, it.value3(), it.value4() == true, it.value5()) }

    fun text(itemId: String, partId: String): Pair<StoredPart, String>? =
        db.selectFrom(PART).where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId)).fetchOne()?.let {
            val content = it.content ?: return null
            StoredPart(it.partId!!, it.position!!, it.title, true, it.publishedAt) to content
        }

    /** The texts of every part of the item that has one, streamed in their order, each with its title. */
    fun forEachContent(itemId: String, action: (title: String?, content: String) -> Unit) {
        db.select(PART.TITLE, PART.CONTENT).from(PART).where(PART.ITEM_ID.eq(itemId), PART.CONTENT.isNotNull).orderBy(PART.POSITION)
            .fetchSize(1).fetchLazy().use { cursor -> cursor.forEach { action(it.value1(), it.value2()!!) } }
    }

    /** The parts whose text has no vectors by [encoder] yet, at most [limit]. */
    fun toEncode(encoder: String, limit: Int): List<PartToEncode> =
        db.select(PART.ITEM_ID, PART.PART_ID, PART.CONTENT).from(PART)
            .where(PART.CONTENT.isNotNull)
            .and(PART.ENCODER.isNull.or(PART.ENCODER.ne(encoder)))
            .limit(limit)
            .fetch { PartToEncode(it.value1()!!, it.value2()!!, it.value3()!!, Vectors.hash(it.value3()!!)) }

    fun countToEncode(encoder: String): Int =
        db.fetchCount(PART, PART.CONTENT.isNotNull.and(PART.ENCODER.isNull.or(PART.ENCODER.ne(encoder))))

    fun countWithText(): Int = db.fetchCount(PART, PART.CONTENT.isNotNull)

    /** Replaces the vectors of the windows of a part, made by [encoder] of the text of [hash]. */
    fun saveVectors(itemId: String, partId: String, encoder: String, hash: String, windows: List<FloatArray>) {
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.deleteFrom(PART_VECTOR).where(PART_VECTOR.ITEM_ID.eq(itemId), PART_VECTOR.PART_ID.eq(partId)).execute()
            windows.forEachIndexed { i, v ->
                tx.insertInto(PART_VECTOR)
                    .set(PART_VECTOR.ITEM_ID, itemId).set(PART_VECTOR.PART_ID, partId).set(PART_VECTOR.WINDOW_NO, i)
                    .set(PART_VECTOR.VEC, Vectors.half(v))
                    .execute()
            }
            tx.update(PART).set(PART.ENCODER, encoder).set(PART.ENCODED_HASH, hash)
                .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId)).execute()
        }
    }

    /** How many window vectors by [encoder] every item has. */
    fun windowCounts(encoder: String): Map<String, Int> =
        db.select(PART_VECTOR.ITEM_ID, DSL.count()).from(PART_VECTOR)
            .join(PART).on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
            .where(PART.ENCODER.eq(encoder))
            .groupBy(PART_VECTOR.ITEM_ID)
            .fetch().associate { it.value1()!! to it.value2()!! }

    /** How many window vectors by [encoder] the item has. */
    fun windowCount(itemId: String, encoder: String): Int =
        db.fetchCount(
            db.select(PART_VECTOR.WINDOW_NO).from(PART_VECTOR)
                .join(PART).on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
                .where(PART_VECTOR.ITEM_ID.eq(itemId), PART.ENCODER.eq(encoder)),
        )

    /** Every window vector by [encoder] of the items. */
    fun windows(itemIds: List<String>, encoder: String): Map<String, List<FloatArray>> {
        val out = HashMap<String, MutableList<FloatArray>>()
        itemIds.chunked(BATCH).forEach { ids ->
            db.select(PART_VECTOR.ITEM_ID, PART_VECTOR.VEC).from(PART_VECTOR)
                .join(PART).on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
                .where(PART_VECTOR.ITEM_ID.`in`(ids), PART.ENCODER.eq(encoder))
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += Vectors.fromHalf(it.value2()!!) }
        }
        return out
    }

    /** The texts of every part of the items [ids] that has one, in their order: item to (part, title, text). */
    fun contentsOf(ids: Collection<String>): Map<String, List<Triple<String, String?, String>>> {
        val out = HashMap<String, MutableList<Triple<String, String?, String>>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, PART.PART_ID, PART.TITLE, PART.CONTENT).from(PART)
                .where(PART.ITEM_ID.`in`(chunk), PART.CONTENT.isNotNull).orderBy(PART.ITEM_ID, PART.POSITION)
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += Triple(it.value2()!!, it.value3(), it.value4()!!) }
        }
        return out
    }

    /** The window vectors by [encoder] of the parts of the items [ids]: item to part to its windows in order. */
    fun windowsByParts(ids: Collection<String>, encoder: String): Map<String, Map<String, List<FloatArray>>> {
        val out = HashMap<String, MutableMap<String, MutableList<FloatArray>>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART_VECTOR.ITEM_ID, PART_VECTOR.PART_ID, PART_VECTOR.VEC).from(PART_VECTOR)
                .join(PART).on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
                .where(PART_VECTOR.ITEM_ID.`in`(chunk), PART.ENCODER.eq(encoder))
                .orderBy(PART_VECTOR.ITEM_ID, PART_VECTOR.PART_ID, PART_VECTOR.WINDOW_NO)
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }.getOrPut(it.value2()!!) { ArrayList() } += Vectors.fromHalf(it.value3()!!) }
        }
        return out
    }

    /** The window vectors by [encoder] of the item's parts: part to its windows in order. */
    fun windowsByPart(itemId: String, encoder: String): Map<String, List<FloatArray>> {
        val out = LinkedHashMap<String, MutableList<FloatArray>>()
        db.select(PART_VECTOR.PART_ID, PART_VECTOR.VEC).from(PART_VECTOR)
            .join(PART).on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
            .where(PART_VECTOR.ITEM_ID.eq(itemId), PART.ENCODER.eq(encoder))
            .orderBy(PART.POSITION, PART_VECTOR.WINDOW_NO)
            .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += Vectors.fromHalf(it.value2()!!) }
        return out
    }

    /** [n] window vectors of the catalogue drawn at random. */
    fun sample(n: Int, encoder: String): List<FloatArray> =
        db.select(PART_VECTOR.VEC).from(PART_VECTOR)
            .join(PART).on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
            .where(PART.ENCODER.eq(encoder))
            .orderBy(DSL.rand()).limit(n)
            .fetch { Vectors.fromHalf(it.value1()!!) }

    companion object {
        private const val BATCH = 500
        private const val UNKNOWN_POSITION = 1_000_000
    }
}
