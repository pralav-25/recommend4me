package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.database.source.tables.records.PictureRecord
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_PICTURE_VECTOR
import io.github.vlsergey.recommend4me.database.source.tables.references.PICTURE
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

data class Picture(
    val itemId: String,
    val position: Int,
    val url: String,
    val previewFile: String?,
    val fullFile: String?,
    /** Analysed by the encoder in use. */
    val analyzed: Boolean,
    val error: String?,
    /** Its downloads failed in a row: what the next wait is counted by ([saveError]). */
    val failures: Int,
)

/**
 * The pictures of the items of one source: what is known of each, what of it is on disk, and its
 * vector by the picture encoder whose id is kept with it. Vectors of another encoder are of another
 * space: a picture counts as analysed only by the encoder in use.
 */
class PictureRepository(private val db: DSLContext) {

    /**
     * The pictures of the item as the site lists them now, by position: 0 the cover, 1.. the
     * screenshots; a null leaves its position empty. A picture whose address changed is a new
     * picture — its files and vector are forgotten. Returns the files no longer anyone's.
     */
    fun sync(itemId: String, urls: List<String?>): List<String> {
        val wanted = urls.withIndex().filter { it.value != null }.associate { it.index to it.value!! }
        return db.transactionResult { c ->
            val tx = DSL.using(c)
            val known = tx.selectFrom(PICTURE).where(PICTURE.ITEM_ID.eq(itemId)).fetch().associateBy { it.position!! }
            val orphans = ArrayList<String>()
            var vectorsChanged = false
            wanted.forEach { (position, url) ->
                val old = known[position]
                if (old == null) {
                    // Another page of the item read at the same moment may have added it already
                    tx.insertInto(PICTURE).set(PICTURE.ITEM_ID, itemId).set(PICTURE.POSITION, position).set(PICTURE.URL, url.take(2000))
                        .onDuplicateKeyIgnore().execute()
                } else if (old.url != url.take(2000)) {
                    orphans += listOfNotNull(old.previewFile, old.fullFile)
                    tx.update(PICTURE)
                        .set(PICTURE.URL, url.take(2000))
                        .setNull(PICTURE.PREVIEW_FILE).setNull(PICTURE.FULL_FILE).setNull(PICTURE.VEC)
                        .setNull(PICTURE.ENCODER).setNull(PICTURE.ANALYZED_AT).setNull(PICTURE.ERROR)
                        .set(PICTURE.FAILURES, 0).setNull(PICTURE.RETRY_AT)
                        .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position))
                        .execute()
                    if (old.vec != null) vectorsChanged = true
                }
            }
            val gone = known.keys - wanted.keys
            gone.forEach { position -> known.getValue(position).let { orphans += listOfNotNull(it.previewFile, it.fullFile) } }
            if (gone.isNotEmpty()) {
                tx.deleteFrom(PICTURE).where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.`in`(gone)).execute()
                if (gone.any { known.getValue(it).vec != null }) vectorsChanged = true
            }
            if (vectorsChanged) tx.deleteFrom(ITEM_PICTURE_VECTOR).where(ITEM_PICTURE_VECTOR.ITEM_ID.eq(itemId)).execute()
            orphans
        }.also { refreshItemVectorsIfAny(itemId) }
    }

    private fun refreshItemVectorsIfAny(itemId: String) {
        val encoder = db.select(PICTURE.ENCODER).from(PICTURE).where(PICTURE.ITEM_ID.eq(itemId), PICTURE.ENCODER.isNotNull).limit(1).fetchOne(PICTURE.ENCODER)
        if (encoder != null) refreshItemVectors(listOf(itemId), encoder)
    }

    fun count(): Int = db.fetchCount(PICTURE)

    /** The pictures analysed by [encoder]; counted by the encoder alone, which its index answers without reading the rows. */
    fun countAnalyzed(encoder: String): Int = db.fetchCount(PICTURE, PICTURE.ENCODER.eq(encoder))

    fun countFailed(): Int = db.fetchCount(PICTURE, PICTURE.ERROR.isNotNull)

    /**
     * The pictures of the items [itemIds] to be worked on: without a vector of [encoder], or of an
     * item in [keep] whose original is not on disk; not failed, or failed and due to be tried
     * again. In the order of [itemIds], every picture of an item together.
     */
    fun pendingOf(itemIds: List<String>, encoder: String, keep: Set<String>, limit: Int): List<Picture> {
        if (itemIds.isEmpty()) return emptyList()
        val out = ArrayList<Picture>()
        itemIds.chunked(BATCH).forEach { chunk ->
            if (out.size >= limit) return@forEach
            val order = chunk.withIndex().associate { (i, id) -> id to i }
            val keepHere = chunk.filter { it in keep }
            out += db.selectFrom(PICTURE)
                .where(PICTURE.ITEM_ID.`in`(chunk), due())
                .and(analyzed(encoder).not().or(if (keepHere.isEmpty()) DSL.falseCondition() else PICTURE.ITEM_ID.`in`(keepHere).and(PICTURE.FULL_FILE.isNull)))
                .fetch { map(it, encoder) }
                .sortedWith(compareBy({ order.getValue(it.itemId) }, { it.position }))
        }
        return out.take(limit)
    }

    /** The items with a picture not analysed by [encoder] yet (not failed, or due to be tried again). */
    fun itemsWithPending(encoder: String): Set<String> =
        db.selectDistinct(PICTURE.ITEM_ID).from(PICTURE).where(due(), analyzed(encoder).not())
            .fetchSet { it.value1()!! }

    fun saveAnalysis(itemId: String, position: Int, vector: FloatArray?, encoder: String, previewFile: String?, fullFile: String?, now: Instant) {
        val update = db.update(PICTURE).set(PICTURE.PREVIEW_FILE, previewFile).set(PICTURE.FULL_FILE, fullFile)
        val withVector = if (vector != null) {
            update.set(PICTURE.VEC, Vectors.half(vector)).set(PICTURE.ENCODER, encoder).set(PICTURE.ANALYZED_AT, now)
        } else update
        // Downloaded: whatever failed before is past
        withVector.setNull(PICTURE.ERROR).set(PICTURE.FAILURES, 0).setNull(PICTURE.RETRY_AT).where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position)).execute()
        if (vector != null) refreshItemVectors(listOf(itemId), encoder)
    }

    fun saveFiles(itemId: String, position: Int, previewFile: String?, fullFile: String?) {
        db.update(PICTURE).set(PICTURE.PREVIEW_FILE, previewFile).set(PICTURE.FULL_FILE, fullFile)
            .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position)).execute()
    }

    /**
     * The picture's download failed the [failures]-th time in a row: tried again at [retryAt], never
     * when it is null — a picture that cannot be decoded, or is gone (404).
     */
    fun saveError(itemId: String, position: Int, error: String, failures: Int, retryAt: Instant?) {
        db.update(PICTURE).set(PICTURE.ERROR, error.take(500)).set(PICTURE.FAILURES, failures).set(PICTURE.RETRY_AT, retryAt)
            .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position))
            .execute()
    }

    /** The earliest moment a failed picture is to be tried again; null when none is. */
    fun nextRetry(): Instant? =
        db.select(DSL.min(PICTURE.RETRY_AT)).from(PICTURE).where(PICTURE.ERROR.isNotNull).fetchOne()?.value1()

    fun find(itemId: String, position: Int, encoder: String?): Picture? =
        db.selectFrom(PICTURE).where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position)).fetchOne { map(it, encoder) }

    fun ofItem(itemId: String, encoder: String?): List<Picture> =
        db.selectFrom(PICTURE).where(PICTURE.ITEM_ID.eq(itemId)).orderBy(PICTURE.POSITION).fetch { map(it, encoder) }

    /** How many pictures every item of [ids] has, and whether it has a cover. */
    fun countsOf(ids: Collection<String>): Map<String, Pair<Int, Boolean>> {
        val out = HashMap<String, Pair<Int, Boolean>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PICTURE.ITEM_ID, PICTURE.POSITION).from(PICTURE).where(PICTURE.ITEM_ID.`in`(chunk))
                .fetch { r ->
                    val (n, cover) = out[r.value1()!!] ?: (0 to false)
                    out[r.value1()!!] = (n + 1) to (cover || r.value2() == 0)
                }
        }
        return out
    }

    /** Drops the originals of an item; returns the files to delete. */
    fun forgetOriginals(itemId: String): List<String> {
        val files = db.select(PICTURE.FULL_FILE).from(PICTURE)
            .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.FULL_FILE.isNotNull)
            .fetch { it.value1()!! }
        db.update(PICTURE).setNull(PICTURE.FULL_FILE).where(PICTURE.ITEM_ID.eq(itemId)).execute()
        return files
    }

    /** Every analysed picture of the item with its position, not combined. */
    fun positioned(itemId: String, encoder: String): List<Pair<Int, FloatArray>> =
        db.select(PICTURE.POSITION, PICTURE.VEC).from(PICTURE)
            .where(PICTURE.ITEM_ID.eq(itemId), analyzed(encoder))
            .orderBy(PICTURE.POSITION)
            .fetch { it.value1()!! to Vectors.fromHalf(it.value2()!!) }

    /** The vectors of the pictures [refs] (item, position) analysed by [encoder], at their current address. */
    fun vectorsOf(refs: Collection<Pair<String, Int>>, encoder: String): Map<Pair<String, Int>, Pair<String, FloatArray>> {
        val out = HashMap<Pair<String, Int>, Pair<String, FloatArray>>()
        refs.map { it.first }.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PICTURE.ITEM_ID, PICTURE.POSITION, PICTURE.URL, PICTURE.VEC).from(PICTURE)
                .where(PICTURE.ITEM_ID.`in`(chunk), analyzed(encoder))
                .fetch { r -> (r.value1()!! to r.value2()!!).takeIf { it in refs }?.let { out[it] = r.value3()!! to Vectors.fromHalf(r.value4()!!) } }
        }
        return out
    }

    /** How many screenshots (not the cover) of every item are analysed. */
    fun screenshotCounts(encoder: String): Map<String, Int> =
        db.select(PICTURE.ITEM_ID, DSL.count()).from(PICTURE)
            .where(PICTURE.POSITION.gt(0), analyzed(encoder))
            .groupBy(PICTURE.ITEM_ID)
            .fetch().associate { it.value1()!! to it.value2()!! }

    /** Every analysed screenshot (not the cover) of the items, one vector each. */
    fun screenshots(itemIds: List<String>, encoder: String): Map<String, List<FloatArray>> {
        val out = HashMap<String, MutableList<FloatArray>>()
        itemIds.chunked(BATCH).forEach { ids ->
            db.select(PICTURE.ITEM_ID, PICTURE.VEC).from(PICTURE)
                .where(PICTURE.ITEM_ID.`in`(ids), PICTURE.POSITION.gt(0), analyzed(encoder))
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += Vectors.fromHalf(it.value2()!!) }
        }
        return out
    }

    /** [n] analysed pictures of the catalogue drawn at random. */
    fun sample(n: Int, encoder: String): List<FloatArray> =
        db.select(PICTURE.VEC).from(PICTURE).where(analyzed(encoder)).orderBy(DSL.rand()).limit(n).fetch { Vectors.fromHalf(it.value1()!!) }

    /**
     * Makes again the stored vectors of the items' pictures ([combine]: the cover, the mean of the
     * screenshots) from their analysed pictures; an item without any loses its rows.
     */
    fun refreshItemVectors(itemIds: Collection<String>, encoder: String) {
        if (itemIds.isEmpty()) return
        itemIds.distinct().chunked(BATCH).forEach { ids ->
            val perItem = HashMap<String, MutableList<Pair<Int, FloatArray>>>()
            db.select(PICTURE.ITEM_ID, PICTURE.POSITION, PICTURE.VEC).from(PICTURE)
                .where(PICTURE.ITEM_ID.`in`(ids), analyzed(encoder))
                .fetch { perItem.getOrPut(it.value1()!!) { ArrayList() } += it.value2()!! to Vectors.fromHalf(it.value3()!!) }
            db.transaction { configuration ->
                val tx = DSL.using(configuration)
                tx.deleteFrom(ITEM_PICTURE_VECTOR).where(ITEM_PICTURE_VECTOR.ITEM_ID.`in`(ids)).execute()
                val rows = perItem.flatMap { (id, list) -> combine(list).map { (block, v) -> Triple(id, block, v) } }
                if (rows.isNotEmpty()) {
                    // Merged, not inserted: two pages of one item read at once both make its vectors,
                    // and the second must replace the first's rows, not fail on them
                    val batch = tx.batch(
                        tx.mergeInto(ITEM_PICTURE_VECTOR, ITEM_PICTURE_VECTOR.ITEM_ID, ITEM_PICTURE_VECTOR.BLOCK, ITEM_PICTURE_VECTOR.ENCODER, ITEM_PICTURE_VECTOR.VEC)
                            .key(ITEM_PICTURE_VECTOR.ITEM_ID, ITEM_PICTURE_VECTOR.BLOCK)
                            .values(null as String?, null as String?, null as String?, null as ByteArray?),
                    )
                    rows.forEach { (id, block, v) -> batch.bind(id, block, encoder, Vectors.half(v)) }
                    batch.execute()
                }
            }
        }
    }

    /** The items with pictures analysed by [encoder] and no stored vectors of them by it. */
    fun itemsWithoutVectors(encoder: String): List<String> =
        db.selectDistinct(PICTURE.ITEM_ID).from(PICTURE)
            .where(analyzed(encoder))
            .andNotExists(
                DSL.selectOne().from(ITEM_PICTURE_VECTOR)
                    .where(ITEM_PICTURE_VECTOR.ITEM_ID.eq(PICTURE.ITEM_ID), ITEM_PICTURE_VECTOR.ENCODER.eq(encoder)),
            )
            .fetch { it.value1()!! }

    /** Every item's stored vectors of its pictures by [encoder], streamed: (item, block key, vector). */
    fun forEachItemVector(encoder: String, action: (itemId: String, key: String, vector: FloatArray) -> Unit) {
        db.select(ITEM_PICTURE_VECTOR.ITEM_ID, ITEM_PICTURE_VECTOR.BLOCK, ITEM_PICTURE_VECTOR.VEC).from(ITEM_PICTURE_VECTOR)
            .where(ITEM_PICTURE_VECTOR.ENCODER.eq(encoder))
            .fetchSize(5000).fetchLazy().use { cursor ->
                cursor.forEach { action(it.value1()!!, it.value2()!!, Vectors.fromHalf(it.value3()!!)) }
            }
    }

    /** The stored vectors of the items [ids]: item to block key to vector. */
    fun itemVectorsOf(ids: Collection<String>, encoder: String): Map<String, Map<String, FloatArray>> {
        val out = HashMap<String, MutableMap<String, FloatArray>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(ITEM_PICTURE_VECTOR.ITEM_ID, ITEM_PICTURE_VECTOR.BLOCK, ITEM_PICTURE_VECTOR.VEC).from(ITEM_PICTURE_VECTOR)
                .where(ITEM_PICTURE_VECTOR.ITEM_ID.`in`(chunk), ITEM_PICTURE_VECTOR.ENCODER.eq(encoder))
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = Vectors.fromHalf(it.value3()!!) }
        }
        return out
    }

    /** Every analysed picture of the catalogue, streamed item by item: (item, its pictures by position). */
    fun forEachItem(encoder: String, action: (itemId: String, pictures: List<Pair<Int, FloatArray>>) -> Unit) {
        var current: String? = null
        val list = ArrayList<Pair<Int, FloatArray>>()
        db.select(PICTURE.ITEM_ID, PICTURE.POSITION, PICTURE.VEC).from(PICTURE)
            .where(analyzed(encoder))
            .orderBy(PICTURE.ITEM_ID)
            .fetchSize(2000).fetchLazy().use { cursor ->
                cursor.forEach {
                    if (it.value1() != current) {
                        current?.let { id -> action(id, list.toList()) }
                        list.clear()
                        current = it.value1()
                    }
                    list += it.value2()!! to Vectors.fromHalf(it.value3()!!)
                }
            }
        current?.let { action(it, list.toList()) }
    }

    private fun map(r: PictureRecord, encoder: String?) = Picture(
        itemId = r.itemId!!,
        position = r.position!!,
        url = r.url!!,
        previewFile = r.previewFile,
        fullFile = r.fullFile,
        analyzed = r.vec != null && encoder != null && r.encoder == encoder,
        error = r.error,
        failures = r.failures!!,
    )

    companion object {
        private const val BATCH = 500

        const val COVER = "img:cover"
        const val SCREENS = "img:screens"

        /** Not failed, or failed and due to be tried again now. */
        private fun due(): Condition = PICTURE.ERROR.isNull.or(PICTURE.RETRY_AT.le(Instant.now()))

        private fun analyzed(encoder: String): Condition = PICTURE.VEC.isNotNull.and(PICTURE.ENCODER.eq(encoder))

        /** Cover alone; the screenshots as their mean direction, unit again. */
        fun combine(list: List<Pair<Int, FloatArray>>): Map<String, FloatArray> {
            val out = HashMap<String, FloatArray>()
            list.firstOrNull { it.first == 0 }?.let { out[COVER] = it.second }
            Vectors.meanDirection(list.filter { it.first > 0 }.map { it.second })?.let { out[SCREENS] = it }
            return out
        }
    }
}
