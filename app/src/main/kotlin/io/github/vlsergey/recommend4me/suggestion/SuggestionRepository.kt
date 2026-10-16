package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.model.tables.references.FACET_MENTION
import io.github.vlsergey.recommend4me.database.model.tables.references.FACET_SUGGESTER
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_VECTOR
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The fitted state of a facet's suggester, the fingerprint of the whole facet it was fitted on, and
 * the values it chooses from ([values]: empty of a state fitted before they were kept).
 */
class StoredSuggester(val suggester: String, val fittedAt: Instant, val basis: Long, val content: ByteArray, val values: List<String>)

/** A facet's model as a work read is scored by ([FittedSuggester.scoring]): of the [suggester], of the values [values] in their order. */
class KeptScoring(val suggester: String, val values: List<String>, val bytes: ByteArray)

/** How a facet's suggester was fitted: what it tried and chose ([FitReport]), how long it took, how many items and values it learnt from. */
class SuggesterFit(val report: FitReport?, val millis: Long, val items: Int, val values: Int)

/** A facet's suggester as the page of the models shows it: which, when fitted, how; [fit] none of one fitted before it was kept. */
class SuggesterStatus(val facet: String, val suggester: String, val fittedAt: Instant, val fit: SuggesterFit?)

/**
 * The chance of a value of an item's facet: one the item had when worked out ([had]), or one it
 * lacked; and how many times the item's texts name it, when they were counted. [draftedFrom]: the
 * value is a draft of the layer ([LayerDraft][io.github.vlsergey.recommend4me.universe.LayerDraft]),
 * read from this value of the site — the work is given it whatever the model's chance (1 when the
 * model has none). Made when the work is read, never kept ([FacetSuggestions.chances]).
 */
data class Chance(
    val itemId: String, val facet: String, val key: String, val had: Boolean, val chance: Double, val mentions: Int? = null,
    val draftedFrom: String? = null,
) {
    val drafted: Boolean get() = draftedFrom != null
}

/**
 * THE MODELS OF ONE SOURCE'S FACETS, in the model database of its content type: the fitted
 * suggester of every facet and the values it chooses from. The chances of the works are not kept:
 * they are made of these when the works are read ([FacetSuggestions.chances]).
 */
class SuggestionRepository(private val db: DSLContext, private val source: String) {

    fun suggester(facet: String): StoredSuggester? =
        db.selectFrom(FACET_SUGGESTER).where(FACET_SUGGESTER.SOURCE.eq(source), FACET_SUGGESTER.FACET.eq(facet)).fetchOne()
            ?.let { StoredSuggester(it.suggester!!, it.fittedAt!!, it.basis!!, it.content!!, it.valueKeys?.split(LINE)?.filter { k -> k.isNotEmpty() }.orEmpty()) }

    fun saveSuggester(facet: String, suggester: String, fittedAt: Instant, basis: Long, content: ByteArray, values: List<String>, scoring: ByteArray?, fit: SuggesterFit) {
        val s = FACET_SUGGESTER
        val report = fit.report?.let(FitReports::toJson)
        val keys = values.joinToString(LINE)
        db.insertInto(s)
            .set(s.SOURCE, source).set(s.FACET, facet).set(s.SUGGESTER, suggester)
            .set(s.FITTED_AT, fittedAt).set(s.BASIS, basis).set(s.CONTENT, content).set(s.VALUE_KEYS, keys).set(s.SCORING, scoring)
            .set(s.REPORT, report).set(s.FIT_MILLIS, fit.millis).set(s.ITEMS, fit.items).set(s.VALUE_COUNT, fit.values)
            .onDuplicateKeyUpdate()
            .set(s.SUGGESTER, suggester).set(s.FITTED_AT, fittedAt)
            .set(s.BASIS, basis).set(s.CONTENT, content).set(s.VALUE_KEYS, keys).set(s.SCORING, scoring)
            .set(s.REPORT, report).set(s.FIT_MILLIS, fit.millis).set(s.ITEMS, fit.items).set(s.VALUE_COUNT, fit.values)
            .execute()
    }

    /** The values the facet's model chooses from, and its scoring of them, kept beside a state fitted before they were. */
    fun keepValues(facet: String, values: List<String>, scoring: ByteArray?) {
        db.update(FACET_SUGGESTER).set(FACET_SUGGESTER.VALUE_KEYS, values.joinToString(LINE)).set(FACET_SUGGESTER.SCORING, scoring)
            .where(FACET_SUGGESTER.SOURCE.eq(source), FACET_SUGGESTER.FACET.eq(facet)).execute()
    }

    /** Whether the facet's model has its scoring kept ([KeptScoring]). */
    fun hasScoring(facet: String): Boolean =
        db.fetchExists(db.selectOne().from(FACET_SUGGESTER).where(FACET_SUGGESTER.SOURCE.eq(source), FACET_SUGGESTER.FACET.eq(facet), FACET_SUGGESTER.SCORING.isNotNull))

    /** The facet's model as a work read is scored by: the scoring kept and the values it is of — not the state fitted, not read for a page. */
    fun scoring(facet: String): KeptScoring? {
        val s = FACET_SUGGESTER
        return db.select(s.SUGGESTER, s.VALUE_KEYS, s.SCORING).from(s).where(s.SOURCE.eq(source), s.FACET.eq(facet), s.SCORING.isNotNull)
            .fetchOne { KeptScoring(it.value1()!!, it.value2()?.split(LINE)?.filter { k -> k.isNotEmpty() }.orEmpty(), it.value3()!!) }
    }

    /**
     * How many times every work's texts name the facet's values, counted at the fitting: work to
     * value to count — of every work counted, a work whose texts name none with none. Replaces the
     * facet's counts of before in one transaction.
     */
    fun keepMentions(facet: String, counts: Map<String, Map<String, Int>>) = db.transaction { c ->
        val tx = DSL.using(c)
        val m = FACET_MENTION
        tx.deleteFrom(m).where(m.SOURCE.eq(source), m.FACET.eq(facet)).execute()
        counts.entries.chunked(BATCH).forEach { chunk ->
            val batch = tx.batch(tx.insertInto(m, m.SOURCE, m.ITEM_ID, m.FACET, m.COUNTS).values(null as String?, null as String?, null as String?, null as String?))
            chunk.forEach { (id, named) -> batch.bind(source, id, facet, named.entries.joinToString(LINE) { (key, n) -> "$key$TAB$n" }) }
            batch.execute()
        }
    }

    /** The counts of [keepMentions] of the works [ids] — of every work counted when null; a work never counted is not among them. */
    fun mentions(facet: String, ids: Collection<String>?): Map<String, Map<String, Int>> {
        val m = FACET_MENTION
        fun read(only: Collection<String>?) = db.select(m.ITEM_ID, m.COUNTS).from(m)
            .where(m.SOURCE.eq(source), m.FACET.eq(facet), only?.let { m.ITEM_ID.`in`(it) } ?: DSL.noCondition())
            .fetchMap({ it.value1()!! }, { r ->
                r.value2()!!.split(LINE).filter { it.isNotEmpty() }.associate { line -> line.substringBeforeLast(TAB) to line.substringAfterLast(TAB).toInt() }
            })
        return if (ids == null) read(null) else ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk -> out.apply { putAll(read(chunk)) } }
    }

    /** The counts of a work gone from the library. */
    fun forget(itemId: String) {
        db.deleteFrom(FACET_MENTION).where(FACET_MENTION.SOURCE.eq(source), FACET_MENTION.ITEM_ID.eq(itemId)).execute()
    }

    /** Every facet's suggester of the source, without its weights. */
    fun suggesters(): List<SuggesterStatus> {
        val s = FACET_SUGGESTER
        return db.select(s.FACET, s.SUGGESTER, s.FITTED_AT, s.REPORT, s.FIT_MILLIS, s.ITEMS, s.VALUE_COUNT).from(s).where(s.SOURCE.eq(source)).fetch { r ->
            val fit = r.value5()?.let { millis -> SuggesterFit(r.value4()?.let(FitReports::fromJson), millis, r.value6() ?: 0, r.value7() ?: 0) }
            SuggesterStatus(r.value1()!!, r.value2()!!, r.value3()!!, fit)
        }
    }

    /** Forgets the models of the facets the model no longer works on: those not in [facets]. */
    fun keepOnly(facets: Collection<String>) {
        db.deleteFrom(FACET_SUGGESTER).where(FACET_SUGGESTER.SOURCE.eq(source), FACET_SUGGESTER.FACET.notIn(facets)).execute()
        db.deleteFrom(FACET_MENTION).where(FACET_MENTION.SOURCE.eq(source), FACET_MENTION.FACET.notIn(facets)).execute()
    }

    private companion object {
        /** The values of a facet kept one a line: a value's key has no line break. */
        const val LINE = "\n"

        /** A value's key and its count, a line of [keepMentions]: a key has no tab. */
        const val TAB = "\t"

        const val BATCH = 1000
    }
}

/** The vectors of the names of facet values of one source, as queries, in its source database. */
class ValueNameRepository(private val db: DSLContext) {

    /** The stored vectors of the facet's values by [encoder] — of the [keys] alone, when given — with the hash of the name each was made of. */
    fun of(facet: String, encoder: String, keys: Collection<String>? = null): Map<String, Pair<String, FloatArray>> {
        fun read(only: Collection<String>?) =
            db.select(FACET_VALUE_VECTOR.VALUE_KEY, FACET_VALUE_VECTOR.NAME_HASH, FACET_VALUE_VECTOR.VEC).from(FACET_VALUE_VECTOR)
                .where(FACET_VALUE_VECTOR.FACET.eq(facet), FACET_VALUE_VECTOR.ENCODER.eq(encoder), only?.let { FACET_VALUE_VECTOR.VALUE_KEY.`in`(it) } ?: DSL.noCondition())
                .fetchMap({ it.value1()!! }, { it.value2()!! to Vectors.fromHalf(it.value3()!!) })
        return if (keys == null) read(null) else keys.distinct().chunked(KEYS).fold(HashMap()) { out, chunk -> out.apply { putAll(read(chunk)) } }
    }

    private companion object {
        /** Keys asked for in one query. */
        const val KEYS = 1000
    }

    /** Every stored vector of the facet's values by [encoder], streamed: (value key, hash of its name, vector) — none of them kept here. */
    fun forEach(facet: String, encoder: String, action: (key: String, hash: String, vector: FloatArray) -> Unit) =
        db.select(FACET_VALUE_VECTOR.VALUE_KEY, FACET_VALUE_VECTOR.NAME_HASH, FACET_VALUE_VECTOR.VEC).from(FACET_VALUE_VECTOR)
            .where(FACET_VALUE_VECTOR.FACET.eq(facet), FACET_VALUE_VECTOR.ENCODER.eq(encoder))
            .fetchSize(2000).fetchLazy().use { cursor -> cursor.forEach { action(it.value1()!!, it.value2()!!, Vectors.fromHalf(it.value3()!!)) } }

    /**
     * One writer at a time: the chances of two works read at once encode the same new names at
     * once, and H2's MERGE of two of them is no merge — the second insert breaks the primary key.
     */
    @Synchronized
    fun save(facet: String, encoder: String, rows: List<Triple<String, String, FloatArray>>) {
        rows.chunked(1000).forEach { chunk ->
            db.batch(chunk.map { (key, hash, vector) ->
                val packed = Vectors.half(vector)
                db.insertInto(FACET_VALUE_VECTOR)
                    .set(FACET_VALUE_VECTOR.FACET, facet).set(FACET_VALUE_VECTOR.VALUE_KEY, key.take(500))
                    .set(FACET_VALUE_VECTOR.ENCODER, encoder).set(FACET_VALUE_VECTOR.NAME_HASH, hash).set(FACET_VALUE_VECTOR.VEC, packed)
                    .onDuplicateKeyUpdate()
                    .set(FACET_VALUE_VECTOR.ENCODER, encoder).set(FACET_VALUE_VECTOR.NAME_HASH, hash).set(FACET_VALUE_VECTOR.VEC, packed)
            }).execute()
        }
    }
}

/** A fitting's report ([FitReport]) as the JSON it is kept as. */
private object FitReports {
    private val JSON = JsonMapper.builder().build()

    fun toJson(r: FitReport): String = JSON.writeValueAsString(
        mapOf(
            "chosen" to r.chosen, "loss" to r.loss, "lossError" to r.lossError, "fromScratch" to r.fromScratch,
            "tried" to r.tried.map { mapOf("settings" to it.settings, "loss" to it.loss, "lossError" to it.lossError, "millis" to it.millis) },
        ),
    )

    /** Null of a text not of this shape: a report is shown, never relied on. */
    fun fromJson(text: String): FitReport? = runCatching {
        val n = JSON.readTree(text)
        fun settings(of: JsonNode) = of.properties().associate { it.key to it.value.asString() }
        FitReport(
            settings(n.get("chosen")), n.get("loss").asDouble(), n.get("lossError").asDouble(),
            n.get("tried").let { t -> (0 until t.size()).map { t.get(it) } }.map { FitTry(settings(it.get("settings")), it.get("loss").asDouble(), it.get("lossError").asDouble(), it.get("millis").asLong()) },
            n.get("fromScratch").asBoolean(),
        )
    }.getOrNull()
}
