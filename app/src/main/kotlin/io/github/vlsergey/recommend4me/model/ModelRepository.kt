package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.model.tables.references.HELD_OUT
import io.github.vlsergey.recommend4me.database.model.tables.references.MODEL
import io.github.vlsergey.recommend4me.database.model.tables.references.PREDICTION
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** A kept model: the scorer that made it, its description and its state. */
class StoredModel(val scorer: String, val meta: String, val weights: ByteArray)

/** The current model of a content type and the prediction of every item, in its model database. */
class ModelRepository(private val db: DSLContext) {

    fun save(scorer: String, trainedAt: Instant, meta: String, weights: ByteArray) {
        db.insertInto(MODEL)
            .set(MODEL.ID, CURRENT).set(MODEL.SCORER, scorer).set(MODEL.TRAINED_AT, trainedAt).set(MODEL.META, meta).set(MODEL.WEIGHTS, weights)
            .onDuplicateKeyUpdate()
            .set(MODEL.SCORER, scorer).set(MODEL.TRAINED_AT, trainedAt).set(MODEL.META, meta).set(MODEL.WEIGHTS, weights)
            .execute()
    }

    fun load(): StoredModel? =
        db.select(MODEL.SCORER, MODEL.META, MODEL.WEIGHTS).from(MODEL).where(MODEL.ID.eq(CURRENT))
            .fetchOne { StoredModel(it.value1()!!, it.value2()!!, it.value3()!!) }

    fun forget() {
        db.transaction { c ->
            DSL.using(c).deleteFrom(MODEL).execute()
            DSL.using(c).deleteFrom(PREDICTION).execute()
            DSL.using(c).deleteFrom(HELD_OUT).execute()
        }
    }

    /** Replaces the predictions of the graded items by the models that had not seen them, in one transaction. */
    fun replaceHeldOut(scores: Map<ItemKey, Double>) {
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.deleteFrom(HELD_OUT).execute()
            scores.entries.chunked(BATCH).forEach { chunk ->
                val batch = tx.batch(
                    tx.insertInto(HELD_OUT, HELD_OUT.SOURCE, HELD_OUT.ITEM_ID, HELD_OUT.SCORE).values(null as String?, null as String?, null as Double?),
                )
                chunk.forEach { (key, score) -> batch.bind(key.source, key.id, score) }
                batch.execute()
            }
        }
    }

    /** Every prediction of a source's graded items by the model that had not seen them: item to score. */
    fun heldOut(source: String): Map<String, Double> =
        db.select(HELD_OUT.ITEM_ID, HELD_OUT.SCORE).from(HELD_OUT).where(HELD_OUT.SOURCE.eq(source))
            .fetch().associate { it.value1()!! to it.value2()!! }

    /**
     * Replaces every item's prediction in one transaction: readers see the old ones until the new
     * ones are committed; nothing else writes this table.
     */
    fun replacePredictions(scores: List<Pair<ItemKey, Double>>) {
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.deleteFrom(PREDICTION).execute()
            scores.chunked(BATCH).forEach { chunk ->
                val batch = tx.batch(
                    tx.insertInto(PREDICTION, PREDICTION.SOURCE, PREDICTION.ITEM_ID, PREDICTION.SCORE).values(null as String?, null as String?, null as Double?),
                )
                chunk.forEach { (key, score) -> batch.bind(key.source, key.id, score) }
                batch.execute()
            }
        }
    }

    fun savePrediction(key: ItemKey, score: Double) {
        db.insertInto(PREDICTION).set(PREDICTION.SOURCE, key.source).set(PREDICTION.ITEM_ID, key.id).set(PREDICTION.SCORE, score)
            .onDuplicateKeyUpdate().set(PREDICTION.SCORE, score)
            .execute()
    }

    /** Every prediction of a source's items: item to score. */
    fun predictions(source: String): Map<String, Double> =
        db.select(PREDICTION.ITEM_ID, PREDICTION.SCORE).from(PREDICTION).where(PREDICTION.SOURCE.eq(source))
            .fetchSize(5000).fetch().associate { it.value1()!! to it.value2()!! }

    fun prediction(key: ItemKey): Double? =
        db.select(PREDICTION.SCORE).from(PREDICTION).where(PREDICTION.SOURCE.eq(key.source), PREDICTION.ITEM_ID.eq(key.id)).fetchOne(PREDICTION.SCORE)

    /** The predictions of the item removed from the library: the model's, and the one that had not seen it. */
    fun forget(key: ItemKey) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.deleteFrom(PREDICTION).where(PREDICTION.SOURCE.eq(key.source), PREDICTION.ITEM_ID.eq(key.id)).execute()
        tx.deleteFrom(HELD_OUT).where(HELD_OUT.SOURCE.eq(key.source), HELD_OUT.ITEM_ID.eq(key.id)).execute()
    }

    companion object {
        private const val CURRENT = 1
        private const val BATCH = 1000
    }
}
