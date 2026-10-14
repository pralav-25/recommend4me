package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.model.tables.references.FACET_SUGGESTER
import io.github.vlsergey.recommend4me.database.model.tables.references.FACET_SUGGESTION
import io.github.vlsergey.recommend4me.database.model.tables.references.FACET_SUGGESTION_BASIS
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_VECTOR
import io.github.vlsergey.recommend4me.draft.DraftRepository
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The fitted state of a facet's suggester, and the fingerprint of the whole facet it was fitted on. */
class StoredSuggester(val suggester: String, val fittedAt: Instant, val basis: Long, val content: ByteArray)

/**
 * The chance of a value of an item's facet: one the item had when worked out ([had]), or one it
 * lacked; and how many times the item's texts name it, when they were counted. [draftedFrom]: the
 * value is a draft of the layer ([LayerDraft][io.github.vlsergey.recommend4me.universe.LayerDraft]),
 * read from this value of the site — the work is given it whatever the model's chance (1 when the
 * model has none).
 */
data class Chance(
    val itemId: String, val facet: String, val key: String, val had: Boolean, val chance: Double, val mentions: Int? = null,
    val draftedFrom: String? = null,
) {
    val drafted: Boolean get() = draftedFrom != null
}

/**
 * What the model says of the values of one source's facets, in the model database of its content
 * type: the fitted suggesters, what every item's chances were made of, and the chances — of every
 * value an item has, and of every value it lacks that it more likely has than not.
 */
class SuggestionRepository(private val db: DSLContext, private val source: String, private val drafts: DraftRepository) {

    /** Held while the chances of items are written: for milliseconds, not for a fitting. */
    private val writing = ReentrantLock()

    fun suggester(facet: String): StoredSuggester? =
        db.selectFrom(FACET_SUGGESTER).where(FACET_SUGGESTER.SOURCE.eq(source), FACET_SUGGESTER.FACET.eq(facet)).fetchOne()
            ?.let { StoredSuggester(it.suggester!!, it.fittedAt!!, it.basis!!, it.content!!) }

    fun saveSuggester(facet: String, suggester: String, fittedAt: Instant, basis: Long, content: ByteArray) {
        db.insertInto(FACET_SUGGESTER)
            .set(FACET_SUGGESTER.SOURCE, source).set(FACET_SUGGESTER.FACET, facet).set(FACET_SUGGESTER.SUGGESTER, suggester)
            .set(FACET_SUGGESTER.FITTED_AT, fittedAt).set(FACET_SUGGESTER.BASIS, basis).set(FACET_SUGGESTER.CONTENT, content)
            .onDuplicateKeyUpdate()
            .set(FACET_SUGGESTER.SUGGESTER, suggester).set(FACET_SUGGESTER.FITTED_AT, fittedAt)
            .set(FACET_SUGGESTER.BASIS, basis).set(FACET_SUGGESTER.CONTENT, content)
            .execute()
    }

    /** The fingerprint the chances of every item of the facet were made from. */
    fun fingerprints(facet: String): Map<String, Long> =
        db.select(FACET_SUGGESTION_BASIS.ITEM_ID, FACET_SUGGESTION_BASIS.FINGERPRINT).from(FACET_SUGGESTION_BASIS)
            .where(FACET_SUGGESTION_BASIS.SOURCE.eq(source), FACET_SUGGESTION_BASIS.FACET.eq(facet))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    fun fingerprint(itemId: String, facet: String): Long? =
        db.select(FACET_SUGGESTION_BASIS.FINGERPRINT).from(FACET_SUGGESTION_BASIS)
            .where(FACET_SUGGESTION_BASIS.SOURCE.eq(source), FACET_SUGGESTION_BASIS.ITEM_ID.eq(itemId), FACET_SUGGESTION_BASIS.FACET.eq(facet))
            .fetchOne()?.value1()

    /**
     * Replaces the chances of the items [made] (item to its fingerprint) of the facet with [chances].
     * One write at a time: an item's chances made at once after an answer and a refit of the whole
     * facet may write the same item together, and the second one's insert would meet the first one's
     * rows, which its delete did not see.
     */
    fun replace(facet: String, made: Map<String, Long>, chances: List<Chance>, now: Instant) {
        if (made.isEmpty()) return
        writing.withLock { write(facet, made, chances, now) }
    }

    private fun write(facet: String, made: Map<String, Long>, chances: List<Chance>, now: Instant) {
        db.transaction { tx ->
            val t = tx.dsl()
            made.keys.chunked(BATCH).forEach { chunk ->
                t.deleteFrom(FACET_SUGGESTION)
                    .where(FACET_SUGGESTION.SOURCE.eq(source), FACET_SUGGESTION.FACET.eq(facet), FACET_SUGGESTION.ITEM_ID.`in`(chunk))
                    .execute()
            }
            chances.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { c ->
                    t.insertInto(FACET_SUGGESTION)
                        .set(FACET_SUGGESTION.SOURCE, source).set(FACET_SUGGESTION.ITEM_ID, c.itemId).set(FACET_SUGGESTION.FACET, facet)
                        .set(FACET_SUGGESTION.VALUE_KEY, c.key.take(500)).set(FACET_SUGGESTION.KIND, if (c.had) HAD else LACKED)
                        .set(FACET_SUGGESTION.CHANCE, c.chance)
                        .set(FACET_SUGGESTION.MENTIONS, c.mentions)
                }).execute()
            }
            made.entries.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { (id, fingerprint) ->
                    t.insertInto(FACET_SUGGESTION_BASIS)
                        .set(FACET_SUGGESTION_BASIS.SOURCE, source).set(FACET_SUGGESTION_BASIS.ITEM_ID, id).set(FACET_SUGGESTION_BASIS.FACET, facet)
                        .set(FACET_SUGGESTION_BASIS.FINGERPRINT, fingerprint).set(FACET_SUGGESTION_BASIS.MADE_AT, now)
                        .onDuplicateKeyUpdate()
                        .set(FACET_SUGGESTION_BASIS.FINGERPRINT, fingerprint).set(FACET_SUGGESTION_BASIS.MADE_AT, now)
                }).execute()
            }
        }
    }

    /** Forgets everything of the facets the model no longer works on: those not in [facets]. */
    fun keepOnly(facets: Collection<String>) {
        db.deleteFrom(FACET_SUGGESTION).where(FACET_SUGGESTION.SOURCE.eq(source), FACET_SUGGESTION.FACET.notIn(facets)).execute()
        db.deleteFrom(FACET_SUGGESTION_BASIS).where(FACET_SUGGESTION_BASIS.SOURCE.eq(source), FACET_SUGGESTION_BASIS.FACET.notIn(facets)).execute()
        db.deleteFrom(FACET_SUGGESTER).where(FACET_SUGGESTER.SOURCE.eq(source), FACET_SUGGESTER.FACET.notIn(facets)).execute()
    }

    /** Every chance of the item removed from the library, and what each facet's were made of. */
    fun forget(itemId: String) {
        db.deleteFrom(FACET_SUGGESTION).where(FACET_SUGGESTION.SOURCE.eq(source), FACET_SUGGESTION.ITEM_ID.eq(itemId)).execute()
        db.deleteFrom(FACET_SUGGESTION_BASIS).where(FACET_SUGGESTION_BASIS.SOURCE.eq(source), FACET_SUGGESTION_BASIS.ITEM_ID.eq(itemId)).execute()
    }

    /**
     * The chances of the items [ids]: item to facet to value to chance — with the drafts of the
     * layer among them ([Chance.draftedFrom]) unless [withDrafts] is false: what the model made
     * alone, as the model compares it with what it makes anew. Of the [facets] alone when given: a
     * card's — a work of a universe keeps the chances of thousands of its characters.
     */
    fun ofItems(ids: Collection<String>, withDrafts: Boolean = true, facets: Collection<String>? = null): Map<String, Map<String, Map<String, Chance>>> {
        val out = HashMap<String, HashMap<String, HashMap<String, Chance>>>()
        if (facets != null && facets.isEmpty()) return out
        fun read(condition: Condition) =
            db.select(FACET_SUGGESTION.ITEM_ID, FACET_SUGGESTION.FACET, FACET_SUGGESTION.VALUE_KEY, FACET_SUGGESTION.KIND, FACET_SUGGESTION.CHANCE, FACET_SUGGESTION.MENTIONS)
                .from(FACET_SUGGESTION)
                .where(FACET_SUGGESTION.SOURCE.eq(source), condition)
                .fetch { r -> put(out, Chance(r.value1()!!, r.value2()!!, r.value3()!!, r.value4() == HAD, r.value5()!!, r.value6())) }
        if (facets == null) ids.distinct().chunked(BATCH).forEach { chunk -> read(FACET_SUGGESTION.ITEM_ID.`in`(chunk)) }
        // An item at a time: the key's index is read by the item and the facets both — the items and
        // the facets each IN would read every value of every facet of the items
        else ids.distinct().forEach { id -> read(FACET_SUGGESTION.ITEM_ID.eq(id).and(FACET_SUGGESTION.FACET.`in`(facets))) }
        if (withDrafts) drafted(out, drafts.ofItems(ids).let { all -> if (facets == null) all else all.mapValues { (_, list) -> list.filter { it.facet in facets } } })
        return out
    }

    /** The chances of every item of the [facets]: item to facet to value to chance, streamed in one pass; the drafts among them ([ofItems]). */
    fun ofFacets(facets: Collection<String>, withDrafts: Boolean = true): Map<String, Map<String, Map<String, Chance>>> {
        val out = HashMap<String, HashMap<String, HashMap<String, Chance>>>()
        if (facets.isEmpty()) return out
        db.select(FACET_SUGGESTION.ITEM_ID, FACET_SUGGESTION.FACET, FACET_SUGGESTION.VALUE_KEY, FACET_SUGGESTION.KIND, FACET_SUGGESTION.CHANCE, FACET_SUGGESTION.MENTIONS)
            .from(FACET_SUGGESTION)
            .where(FACET_SUGGESTION.SOURCE.eq(source), FACET_SUGGESTION.FACET.`in`(facets))
            .fetchSize(5000).fetchLazy().use { cursor ->
                cursor.forEach { r -> put(out, Chance(r.value1()!!, r.value2()!!, r.value3()!!, r.value4() == HAD, r.value5()!!, r.value6())) }
            }
        if (withDrafts) drafted(out, drafts.ofFacets(facets))
        return out
    }

    /**
     * The values of the [facet] the model alone gives every item — those it lacked and more likely
     * has than not ([ModelValues.given] without the drafts) — of the item [only], when given: what a
     * refresh compares the new ones with, read of that facet alone, not every chance of every facet.
     */
    fun givenOf(facet: String, only: String? = null): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        db.select(FACET_SUGGESTION.ITEM_ID, FACET_SUGGESTION.VALUE_KEY).from(FACET_SUGGESTION)
            .where(
                FACET_SUGGESTION.SOURCE.eq(source), only?.let { FACET_SUGGESTION.ITEM_ID.eq(it) } ?: DSL.noCondition(), FACET_SUGGESTION.FACET.eq(facet),
                FACET_SUGGESTION.KIND.eq(LACKED), FACET_SUGGESTION.CHANCE.gt(ModelValues.LIKELY),
            )
            .fetchSize(5000).fetchLazy().use { cursor -> cursor.forEach { out.getOrPut(it.value1()!!) { HashSet() } += it.value2()!! } }
        return out
    }

    /** Every draft a value of its item's chances, marked so: the model's chance of it kept, 1 when the model has none. */
    private fun drafted(out: HashMap<String, HashMap<String, HashMap<String, Chance>>>, drafts: Map<String, List<LayerDraft>>) {
        drafts.forEach { (id, list) ->
            list.forEach { d ->
                val byValue = out.getOrPut(id) { HashMap() }.getOrPut(d.facet) { HashMap() }
                byValue[d.key] = byValue[d.key]?.copy(draftedFrom = d.from) ?: Chance(id, d.facet, d.key, had = false, chance = 1.0, draftedFrom = d.from)
            }
        }
    }

    private fun put(out: HashMap<String, HashMap<String, HashMap<String, Chance>>>, c: Chance) {
        out.getOrPut(c.itemId) { HashMap() }.getOrPut(c.facet) { HashMap() }[c.key] = c
    }

    companion object {
        private const val BATCH = 1000
        private const val HAD = "A"
        private const val LACKED = "S"
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
     * One writer at a time: the chances of a work made after an answer and those of the page read
     * meanwhile encode the same new names at once, and H2's MERGE of two of them is no merge — the
     * second insert breaks the primary key.
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
