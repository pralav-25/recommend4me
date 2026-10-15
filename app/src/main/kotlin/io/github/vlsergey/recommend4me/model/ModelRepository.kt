package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.model.tables.references.HELD_OUT
import io.github.vlsergey.recommend4me.database.model.tables.references.HELD_OUT_EXPLAINED
import io.github.vlsergey.recommend4me.database.model.tables.references.MODEL
import io.github.vlsergey.recommend4me.database.model.tables.references.PREDICTION
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** A kept model: the scorer that made it, its description and its state. */
class StoredModel(val scorer: String, val meta: String, val weights: ByteArray)

/** What the model that had not seen a graded work says of it, as kept: no [score] when there were too few works for folds; the [explanation] as JSON. */
class Explained(val score: Double?, val gradePlace: Double?, val explanation: String?)

/** An item's score on 0..10, and its chance of interest, 0..1, when there is a model of interest. */
class Predicted(val score: Double, val interest: Double?)

/** The current models of a content type — of the grades and of interest — and the prediction of every item, in its model database. */
class ModelRepository(private val db: DSLContext) {

    fun save(scorer: String, trainedAt: Instant, meta: String, weights: ByteArray) = save(CURRENT, scorer, trainedAt, meta, weights)

    fun load(): StoredModel? = load(CURRENT)

    fun saveInterest(scorer: String, trainedAt: Instant, meta: String, weights: ByteArray) = save(INTEREST, scorer, trainedAt, meta, weights)

    fun loadInterest(): StoredModel? = load(INTEREST)

    /** The model of interest, when there are too few marks of either kind to train one. */
    fun forgetInterest() {
        db.deleteFrom(MODEL).where(MODEL.ID.eq(INTEREST)).execute()
    }

    /** A model saved; of the grades, what the models that had not seen the graded works said of them goes: they were of the model before. */
    private fun save(id: Int, scorer: String, trainedAt: Instant, meta: String, weights: ByteArray) {
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.insertInto(MODEL)
                .set(MODEL.ID, id).set(MODEL.SCORER, scorer).set(MODEL.TRAINED_AT, trainedAt).set(MODEL.META, meta).set(MODEL.WEIGHTS, weights)
                .onDuplicateKeyUpdate()
                .set(MODEL.SCORER, scorer).set(MODEL.TRAINED_AT, trainedAt).set(MODEL.META, meta).set(MODEL.WEIGHTS, weights)
                .execute()
            if (id == CURRENT) tx.deleteFrom(HELD_OUT_EXPLAINED).execute()
        }
    }

    /** What the model that had not seen the graded work says of it, as worked out; null while it is not. */
    fun explained(key: ItemKey): Explained? =
        db.select(HELD_OUT_EXPLAINED.SCORE, HELD_OUT_EXPLAINED.GRADE_PLACE, HELD_OUT_EXPLAINED.EXPLANATION).from(HELD_OUT_EXPLAINED)
            .where(HELD_OUT_EXPLAINED.SOURCE.eq(key.source), HELD_OUT_EXPLAINED.ITEM_ID.eq(key.id))
            .fetchOne { Explained(it.value1(), it.value2(), it.value3()) }

    /** Keeps what the model that had not seen the graded work says of it: its [score], none for a work with too few works for folds. */
    fun keepExplained(key: ItemKey, score: Double?, gradePlace: Double?, explanation: String?) {
        db.insertInto(HELD_OUT_EXPLAINED)
            .set(HELD_OUT_EXPLAINED.SOURCE, key.source).set(HELD_OUT_EXPLAINED.ITEM_ID, key.id)
            .set(HELD_OUT_EXPLAINED.SCORE, score).set(HELD_OUT_EXPLAINED.GRADE_PLACE, gradePlace).set(HELD_OUT_EXPLAINED.EXPLANATION, explanation)
            .onDuplicateKeyUpdate()
            .set(HELD_OUT_EXPLAINED.SCORE, score).set(HELD_OUT_EXPLAINED.GRADE_PLACE, gradePlace).set(HELD_OUT_EXPLAINED.EXPLANATION, explanation)
            .execute()
    }

    /** What was worked out of the graded work goes: a word on it changed what it is made of. */
    fun forgetExplained(key: ItemKey) {
        db.deleteFrom(HELD_OUT_EXPLAINED).where(HELD_OUT_EXPLAINED.SOURCE.eq(key.source), HELD_OUT_EXPLAINED.ITEM_ID.eq(key.id)).execute()
    }

    private fun load(id: Int): StoredModel? =
        db.select(MODEL.SCORER, MODEL.META, MODEL.WEIGHTS).from(MODEL).where(MODEL.ID.eq(id))
            .fetchOne { StoredModel(it.value1()!!, it.value2()!!, it.value3()!!) }

    /** Both models and every prediction. */
    fun forget() {
        db.transaction { c ->
            DSL.using(c).deleteFrom(MODEL).execute()
            DSL.using(c).deleteFrom(PREDICTION).execute()
            DSL.using(c).deleteFrom(HELD_OUT).execute()
            DSL.using(c).deleteFrom(HELD_OUT_EXPLAINED).execute()
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
     * ones are committed; nothing else writes this table. Only what changed is written — a score
     * or a chance of interest beyond [STEP], an item new or gone: most of a retraining's scores come
     * out as they were, and a file rewritten whole on every training only grows.
     */
    fun replacePredictions(predicted: List<Pair<ItemKey, Predicted>>) {
        fun same(a: Double?, b: Double?) = if (a == null || b == null) a == b else kotlin.math.abs(a - b) < STEP
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            val stored = HashMap<ItemKey, Pair<Double?, Double?>>()
            tx.select(PREDICTION.SOURCE, PREDICTION.ITEM_ID, PREDICTION.SCORE, PREDICTION.INTEREST).from(PREDICTION)
                .fetchSize(5000).fetchLazy().use { cursor -> cursor.forEach { stored[ItemKey(it.value1()!!, it.value2()!!)] = it.value3() to it.value4() } }
            val wanted = predicted.toMap()
            val gone = stored.keys - wanted.keys
            gone.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
                ids.chunked(BATCH).forEach { chunk -> tx.deleteFrom(PREDICTION).where(PREDICTION.SOURCE.eq(source), PREDICTION.ITEM_ID.`in`(chunk)).execute() }
            }
            val changed = predicted.filter { (key, p) -> stored[key]?.let { (score, interest) -> same(score, p.score) && same(interest, p.interest) } != true }
            changed.chunked(BATCH).forEach { chunk ->
                tx.batch(chunk.map { (key, p) ->
                    tx.insertInto(PREDICTION)
                        .set(PREDICTION.SOURCE, key.source).set(PREDICTION.ITEM_ID, key.id).set(PREDICTION.SCORE, p.score).set(PREDICTION.INTEREST, p.interest)
                        .onDuplicateKeyUpdate().set(PREDICTION.SCORE, p.score).set(PREDICTION.INTEREST, p.interest)
                }).execute()
            }
        }
    }

    fun savePrediction(key: ItemKey, p: Predicted) {
        db.insertInto(PREDICTION)
            .set(PREDICTION.SOURCE, key.source).set(PREDICTION.ITEM_ID, key.id).set(PREDICTION.SCORE, p.score).set(PREDICTION.INTEREST, p.interest)
            .onDuplicateKeyUpdate().set(PREDICTION.SCORE, p.score).set(PREDICTION.INTEREST, p.interest)
            .execute()
    }

    /** Every prediction of a source's items: item to score. */
    fun predictions(source: String): Map<String, Double> =
        db.select(PREDICTION.ITEM_ID, PREDICTION.SCORE).from(PREDICTION).where(PREDICTION.SOURCE.eq(source))
            .fetchSize(5000).fetch().associate { it.value1()!! to it.value2()!! }

    /** Every prediction of a source's items with its chance of interest: item to both. */
    fun predicted(source: String): Map<String, Predicted> =
        db.select(PREDICTION.ITEM_ID, PREDICTION.SCORE, PREDICTION.INTEREST).from(PREDICTION).where(PREDICTION.SOURCE.eq(source))
            .fetchSize(5000).fetch().associate { it.value1()!! to Predicted(it.value2()!!, it.value3()) }

    fun prediction(key: ItemKey): Double? =
        db.select(PREDICTION.SCORE).from(PREDICTION).where(PREDICTION.SOURCE.eq(key.source), PREDICTION.ITEM_ID.eq(key.id)).fetchOne(PREDICTION.SCORE)

    /** The predictions of the item removed from the library: the model's, and the one that had not seen it. */
    fun forget(key: ItemKey) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.deleteFrom(PREDICTION).where(PREDICTION.SOURCE.eq(key.source), PREDICTION.ITEM_ID.eq(key.id)).execute()
        tx.deleteFrom(HELD_OUT).where(HELD_OUT.SOURCE.eq(key.source), HELD_OUT.ITEM_ID.eq(key.id)).execute()
        tx.deleteFrom(HELD_OUT_EXPLAINED).where(HELD_OUT_EXPLAINED.SOURCE.eq(key.source), HELD_OUT_EXPLAINED.ITEM_ID.eq(key.id)).execute()
    }

    companion object {
        private const val CURRENT = 1
        private const val INTEREST = 2
        private const val BATCH = 1000

        /** A score or a chance changed by less is not written again: a retraining's noise, unseen on the 0..10 scale. */
        private const val STEP = 1e-4
    }
}
