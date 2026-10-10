package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.database.model.tables.references.MARK_LIKENESS
import io.github.vlsergey.recommend4me.database.model.tables.references.MARK_LIKENESS_AXES
import io.github.vlsergey.recommend4me.database.model.tables.references.MARK_LIKENESS_STRENGTH
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** What the stored likeness of a kind was made of: the marks and the words on them, and how many items the catalogue had. */
data class LikenessMade(val fingerprint: Long, val items: Long)

/** The likeness of the items of a content type to the user's marks, kept in its model database. */
class MarkLikenessRepository(private val db: DSLContext) {

    fun made(kind: MarkKind): LikenessMade? =
        db.select(MARK_LIKENESS.FINGERPRINT, MARK_LIKENESS.ITEMS).from(MARK_LIKENESS).where(MARK_LIKENESS.MARK_KIND.eq(kind.name))
            .fetchOne { LikenessMade(it.value1()!!, it.value2()!!) }

    /**
     * The stored likeness of a kind for a training: the strengths to every mark of the items
     * [whole] — the graded ones, whose folds leave out the marks of the works under test — and the
     * four axes of all the others.
     */
    fun load(kind: MarkKind, whole: Collection<ItemKey>): MarkLikeness? {
        val calibration = calibration(kind) ?: return null
        val strengths = HashMap<ItemKey, FloatArray>()
        whole.groupBy { it.source }.forEach { (source, keys) ->
            keys.map { it.id }.chunked(1000).forEach { ids ->
                db.select(MARK_LIKENESS_STRENGTH.ITEM_ID, MARK_LIKENESS_STRENGTH.STRENGTH).from(MARK_LIKENESS_STRENGTH)
                    .where(MARK_LIKENESS_STRENGTH.MARK_KIND.eq(kind.name), MARK_LIKENESS_STRENGTH.SOURCE.eq(source), MARK_LIKENESS_STRENGTH.ITEM_ID.`in`(ids))
                    .fetch { strengths[ItemKey(source, it.value1()!!)] = Vectors.unpack(it.value2()!!) }
            }
        }
        val axes = HashMap<ItemKey, FloatArray>()
        db.select(MARK_LIKENESS_AXES.SOURCE, MARK_LIKENESS_AXES.ITEM_ID, MARK_LIKENESS_AXES.AXES).from(MARK_LIKENESS_AXES)
            .where(MARK_LIKENESS_AXES.MARK_KIND.eq(kind.name))
            .fetchSize(5000).fetchLazy().use { cursor -> cursor.forEach { axes[ItemKey(it.value1()!!, it.value2()!!)] = Vectors.unpack(it.value3()!!) } }
        return MarkLikeness.unpack(calibration, strengths, axes)
    }

    /** The stored likeness of a kind without the items' rows: what one item's likeness is measured with. */
    fun loadCalibration(kind: MarkKind): MarkLikeness? = calibration(kind)?.let { MarkLikeness.unpack(it, emptyMap()) }

    private fun calibration(kind: MarkKind): ByteArray? =
        db.select(MARK_LIKENESS.CALIBRATION).from(MARK_LIKENESS).where(MARK_LIKENESS.MARK_KIND.eq(kind.name)).fetchOne { it.value1() }

    /** Replaces the stored likeness of a kind, in one transaction. */
    fun save(kind: MarkKind, likeness: MarkLikeness, made: LikenessMade, now: Instant) {
        val calibration = likeness.pack()
        val strengths = likeness.strengths()
        val axes = likeness.axes()
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.deleteFrom(MARK_LIKENESS).where(MARK_LIKENESS.MARK_KIND.eq(kind.name)).execute()
            tx.insertInto(MARK_LIKENESS)
                .set(MARK_LIKENESS.MARK_KIND, kind.name).set(MARK_LIKENESS.MADE_AT, now)
                .set(MARK_LIKENESS.FINGERPRINT, made.fingerprint).set(MARK_LIKENESS.ITEMS, made.items)
                .set(MARK_LIKENESS.CALIBRATION, calibration)
                .execute()
            tx.deleteFrom(MARK_LIKENESS_STRENGTH).where(MARK_LIKENESS_STRENGTH.MARK_KIND.eq(kind.name)).execute()
            tx.deleteFrom(MARK_LIKENESS_AXES).where(MARK_LIKENESS_AXES.MARK_KIND.eq(kind.name)).execute()
            strengths.entries.chunked(BATCH).forEach { chunk ->
                val batch = tx.batch(
                    tx.insertInto(MARK_LIKENESS_STRENGTH, MARK_LIKENESS_STRENGTH.MARK_KIND, MARK_LIKENESS_STRENGTH.SOURCE, MARK_LIKENESS_STRENGTH.ITEM_ID, MARK_LIKENESS_STRENGTH.STRENGTH)
                        .values(null as String?, null as String?, null as String?, null as ByteArray?),
                )
                chunk.forEach { (key, s) -> batch.bind(kind.name, key.source, key.id, Vectors.pack(s)) }
                batch.execute()
            }
            axes.entries.chunked(BATCH).forEach { chunk ->
                val batch = tx.batch(
                    tx.insertInto(MARK_LIKENESS_AXES, MARK_LIKENESS_AXES.MARK_KIND, MARK_LIKENESS_AXES.SOURCE, MARK_LIKENESS_AXES.ITEM_ID, MARK_LIKENESS_AXES.AXES)
                        .values(null as String?, null as String?, null as String?, null as ByteArray?),
                )
                chunk.forEach { (key, v) -> batch.bind(kind.name, key.source, key.id, Vectors.pack(v)) }
                batch.execute()
            }
        }
    }

    /** The likeness of the item removed from the library to the marks, of every kind; made again without it at the next training. */
    fun forget(key: ItemKey) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.deleteFrom(MARK_LIKENESS_STRENGTH).where(MARK_LIKENESS_STRENGTH.SOURCE.eq(key.source), MARK_LIKENESS_STRENGTH.ITEM_ID.eq(key.id)).execute()
        tx.deleteFrom(MARK_LIKENESS_AXES).where(MARK_LIKENESS_AXES.SOURCE.eq(key.source), MARK_LIKENESS_AXES.ITEM_ID.eq(key.id)).execute()
    }

    companion object {
        private const val BATCH = 1000
    }
}
