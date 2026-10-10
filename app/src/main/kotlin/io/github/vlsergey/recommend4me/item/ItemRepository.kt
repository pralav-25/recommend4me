package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.CAPTURED_PAGE
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_FACET
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_NUMBER
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_TEXT
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_VERSION
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.StoredItem
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.Instant

/**
 * The items of one source as its source database holds them: what every item has, its facets,
 * numbers and texts. [changed] is told of every item whose searchable data was written.
 */
class ItemRepository(
    private val db: DSLContext,
    private val changed: (Collection<String>) -> Unit,
) {

    /** Creates or updates an item; true when it is new or its version changed. */
    fun upsert(item: ItemHead, now: Instant): Boolean {
        val known = db.select(ITEM.VERSION, ITEM.TITLE, ITEM.URL, ITEM.UPDATED_AT).from(ITEM).where(ITEM.ITEM_ID.eq(item.id)).fetchOne()
        val updatedAt = item.updatedAt ?: known?.value4() ?: now
        if (known == null) {
            db.insertInto(ITEM)
                .set(ITEM.ITEM_ID, item.id).set(ITEM.URL, item.url).set(ITEM.TITLE, item.title).set(ITEM.VERSION, item.version)
                .set(ITEM.UPDATED_AT, updatedAt).set(ITEM.FIRST_SEEN_AT, now)
                .execute()
        } else if (known.value1() != item.version || known.value2() != item.title || known.value3() != item.url || known.value4() != updatedAt) {
            db.update(ITEM)
                .set(ITEM.URL, item.url).set(ITEM.TITLE, item.title).set(ITEM.VERSION, item.version).set(ITEM.UPDATED_AT, updatedAt)
                .where(ITEM.ITEM_ID.eq(item.id))
                .execute()
        }
        val changedVersion = known?.value1() != item.version
        if (changedVersion) {
            db.insertInto(ITEM_VERSION)
                .set(ITEM_VERSION.ITEM_ID, item.id).set(ITEM_VERSION.VERSION, item.version)
                .set(ITEM_VERSION.UPDATED_AT, updatedAt).set(ITEM_VERSION.SEEN_AT, now)
                .onDuplicateKeyIgnore()
                .execute()
        }
        if (known == null || known.value2() != item.title) changed(listOf(item.id))
        return changedVersion
    }

    fun find(itemId: String): StoredItem? =
        db.select(HEAD).from(ITEM).where(ITEM.ITEM_ID.eq(itemId)).fetchOne(::head)

    /** The items [ids], in one query per thousand. */
    fun heads(ids: Collection<String>): Map<String, StoredItem> {
        val out = HashMap<String, StoredItem>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(HEAD).from(ITEM).where(ITEM.ITEM_ID.`in`(chunk)).fetch { out[it.get(ITEM.ITEM_ID)!!] = head(it) }
        }
        return out
    }

    /** Every item, streamed. */
    fun forEachHead(action: (StoredItem) -> Unit) {
        db.select(HEAD).from(ITEM).fetchSize(2000).fetchLazy().use { cursor -> cursor.forEach { action(head(it)) } }
    }

    /** What the list of a content type sorts and filters by, of every item: id, version, update. */
    fun keys(): List<ItemKeys> =
        db.select(ITEM.ITEM_ID, ITEM.VERSION, ITEM.UPDATED_AT).from(ITEM).fetchSize(5000)
            .fetch { ItemKeys(it.value1()!!, it.value2()!!, it.value3()!!) }

    fun count(): Int = db.fetchCount(ITEM)

    fun exists(itemId: String): Boolean = db.fetchExists(ITEM, ITEM.ITEM_ID.eq(itemId))

    /**
     * The item removed from the library: everything the source database holds of it — its versions,
     * values, texts, pictures, reviews, parts and every vector of them go with it — and the pages
     * kept of it alone. Nothing tells it was there: seen on the site again, it is a work never seen.
     */
    fun remove(itemId: String) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(CAPTURED_PAGE).where(CAPTURED_PAGE.ITEM_ID.eq(itemId)).execute()
        tx.deleteFrom(ITEM).where(ITEM.ITEM_ID.eq(itemId)).execute()
    }

    fun latestUpdate(): Instant? = db.select(DSL.max(ITEM.UPDATED_AT)).from(ITEM).fetchOne()?.value1()

    // --- Facets ---

    fun setFacet(itemId: String, facet: String, values: List<FacetValue>) {
        val distinct = values.distinctBy { it.key }
        val before = db.select(ITEM_FACET.VALUE_KEY).from(ITEM_FACET)
            .where(ITEM_FACET.ITEM_ID.eq(itemId), ITEM_FACET.FACET.eq(facet)).orderBy(ITEM_FACET.POSITION)
            .fetch { it.value1()!! }
        if (before != distinct.map { it.key }) {
            db.transaction { c ->
                val tx = DSL.using(c)
                tx.deleteFrom(ITEM_FACET).where(ITEM_FACET.ITEM_ID.eq(itemId), ITEM_FACET.FACET.eq(facet)).execute()
                distinct.forEachIndexed { i, v ->
                    tx.insertInto(ITEM_FACET)
                        .set(ITEM_FACET.ITEM_ID, itemId).set(ITEM_FACET.FACET, facet)
                        .set(ITEM_FACET.VALUE_KEY, v.key.take(500)).set(ITEM_FACET.POSITION, i)
                        .execute()
                }
            }
            changed(listOf(itemId))
        }
        val named = distinct.filter { it.name != null }.associate { it.key to it.name!! }
        if (named.isNotEmpty()) nameFacetValues(facet, named)
    }

    /** Removes the values and the names of every facet not among [facets]; how many values were removed of each. */
    fun keepOnlyFacets(facets: Collection<String>): Map<String, Int> {
        val gone = db.select(ITEM_FACET.FACET).from(ITEM_FACET).where(ITEM_FACET.FACET.notIn(facets)).fetch(ITEM_FACET.FACET)
            .groupingBy { it!! }.eachCount()
        if (gone.isNotEmpty()) db.deleteFrom(ITEM_FACET).where(ITEM_FACET.FACET.notIn(facets)).execute()
        db.deleteFrom(FACET_VALUE).where(FACET_VALUE.FACET.notIn(facets)).execute()
        return gone
    }

    /**
     * The names of the facet's values written where new or changed. One writer at a time: a page's
     * reading and the chances made after an answer name the same values at once, and H2's MERGE of
     * two of them is no merge — the second insert breaks the primary key.
     */
    @Synchronized
    fun nameFacetValues(facet: String, names: Map<String, String>) {
        if (names.isEmpty()) return
        val known = facetNames(facet, names.keys)
        names.filter { (key, name) -> known[key] != name }.forEach { (key, name) ->
            db.insertInto(FACET_VALUE).set(FACET_VALUE.FACET, facet).set(FACET_VALUE.VALUE_KEY, key.take(500)).set(FACET_VALUE.NAME, name.take(1000))
                .onDuplicateKeyUpdate().set(FACET_VALUE.NAME, name.take(1000))
                .execute()
        }
    }

    /**
     * The names of the values [keys] of a facet; a key without a name has none here. One pass over
     * the facet's names, the keys looked up here: H2 takes a list of keys bound to the query not
     * for the primary key but for a condition of every row of the facet, every key of it compared
     * with every row — of the thousands of characters most of the time of reading what a model learns.
     */
    fun facetNames(facet: String, keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val wanted = keys as? Set<String> ?: keys.toHashSet()
        val out = HashMap<String, String>()
        db.select(FACET_VALUE.VALUE_KEY, FACET_VALUE.NAME).from(FACET_VALUE).where(FACET_VALUE.FACET.eq(facet))
            .fetchSize(5000).fetchLazy().use { cursor -> cursor.forEach { if (it.value1()!! in wanted) out[it.value1()!!] = it.value2()!! } }
        return out
    }

    /** Every named value of one facet: key to name. */
    fun facetNames(facet: String): Map<String, String> =
        db.select(FACET_VALUE.VALUE_KEY, FACET_VALUE.NAME).from(FACET_VALUE).where(FACET_VALUE.FACET.eq(facet))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** How many items have each value of a facet, as the site gives them. */
    fun valueUses(facet: String): Map<String, Int> =
        db.select(ITEM_FACET.VALUE_KEY, DSL.count()).from(ITEM_FACET).where(ITEM_FACET.FACET.eq(facet))
            .groupBy(ITEM_FACET.VALUE_KEY).fetchMap({ it.value1()!! }, { it.value2()!! })

    /** The items whose title holds [piece], any case, the newest first. */
    fun findByTitle(piece: String, limit: Int): List<StoredItem> =
        db.select(HEAD).from(ITEM).where(DSL.lower(ITEM.TITLE).contains(piece.lowercase()))
            .orderBy(ITEM.UPDATED_AT.desc()).limit(limit).fetch(::head)

    /** Every named value of every facet: facet to key to name. */
    fun allFacetNames(): Map<String, Map<String, String>> =
        db.select(FACET_VALUE.FACET, FACET_VALUE.VALUE_KEY, FACET_VALUE.NAME).from(FACET_VALUE).fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! }).mapValues { it.value.toMap() }

    fun facets(itemId: String): Map<String, List<String>> =
        db.select(ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY).from(ITEM_FACET).where(ITEM_FACET.ITEM_ID.eq(itemId))
            .orderBy(ITEM_FACET.FACET, ITEM_FACET.POSITION)
            .fetch().groupBy({ it.value1()!! }, { it.value2()!! })

    /** The facets of the items [ids]: item to facet to keys in order. */
    fun facetsOf(ids: Collection<String>): Map<String, Map<String, List<String>>> {
        val out = HashMap<String, MutableMap<String, MutableList<String>>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY).from(ITEM_FACET)
                .where(ITEM_FACET.ITEM_ID.`in`(chunk)).orderBy(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.POSITION)
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }.getOrPut(it.value2()!!) { ArrayList() } += it.value3()!! }
        }
        return out
    }

    /** The items the site gives each of the [keys] of the facet: key to items. */
    fun itemsWith(facet: String, keys: Collection<String>): Map<String, List<String>> {
        if (keys.isEmpty()) return emptyMap()
        return db.select(ITEM_FACET.VALUE_KEY, ITEM_FACET.ITEM_ID).from(ITEM_FACET)
            .where(ITEM_FACET.FACET.eq(facet), ITEM_FACET.VALUE_KEY.`in`(keys.distinct()))
            .fetch().groupBy({ it.value1()!! }, { it.value2()!! })
    }

    /** Every item's keys of one facet, streamed item by item. */
    fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit) {
        var current: String? = null
        val list = ArrayList<String>()
        db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.VALUE_KEY).from(ITEM_FACET).where(ITEM_FACET.FACET.eq(facet))
            .orderBy(ITEM_FACET.ITEM_ID, ITEM_FACET.POSITION)
            .fetchSize(5000).fetchLazy().use { cursor ->
                cursor.forEach {
                    if (it.value1() != current) {
                        current?.let { id -> action(id, list.toList()) }
                        list.clear()
                        current = it.value1()
                    }
                    list += it.value2()!!
                }
            }
        current?.let { action(it, list.toList()) }
    }

    /** Every facet of every item, streamed: (item, facet, key). */
    fun forEachFacetValue(action: (itemId: String, facet: String, key: String) -> Unit) {
        db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY).from(ITEM_FACET)
            .fetchSize(5000).fetchLazy().use { cursor -> cursor.forEach { action(it.value1()!!, it.value2()!!, it.value3()!!) } }
    }

    // --- Numbers ---

    fun setNumbers(itemId: String, numbers: Map<String, Double?>) {
        numbers.forEach { (key, value) ->
            if (value == null || value.isNaN()) {
                db.deleteFrom(ITEM_NUMBER).where(ITEM_NUMBER.ITEM_ID.eq(itemId), ITEM_NUMBER.NUMBER_KEY.eq(key)).execute()
            } else {
                db.insertInto(ITEM_NUMBER).set(ITEM_NUMBER.ITEM_ID, itemId).set(ITEM_NUMBER.NUMBER_KEY, key).set(ITEM_NUMBER.CONTENT, value)
                    .onDuplicateKeyUpdate().set(ITEM_NUMBER.CONTENT, value)
                    .execute()
            }
        }
    }

    fun numbers(itemId: String): Map<String, Double> =
        db.select(ITEM_NUMBER.NUMBER_KEY, ITEM_NUMBER.CONTENT).from(ITEM_NUMBER).where(ITEM_NUMBER.ITEM_ID.eq(itemId))
            .fetch().associate { it.value1()!! to it.value2()!! }

    fun numbersOf(ids: Collection<String>): Map<String, Map<String, Double>> {
        val out = HashMap<String, MutableMap<String, Double>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(ITEM_NUMBER.ITEM_ID, ITEM_NUMBER.NUMBER_KEY, ITEM_NUMBER.CONTENT).from(ITEM_NUMBER)
                .where(ITEM_NUMBER.ITEM_ID.`in`(chunk))
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = it.value3()!! }
        }
        return out
    }

    /** Every number of every item, streamed: (item, key, value). */
    fun forEachNumber(action: (itemId: String, key: String, value: Double) -> Unit) {
        db.select(ITEM_NUMBER.ITEM_ID, ITEM_NUMBER.NUMBER_KEY, ITEM_NUMBER.CONTENT).from(ITEM_NUMBER)
            .fetchSize(5000).fetchLazy().use { cursor -> cursor.forEach { action(it.value1()!!, it.value2()!!, it.value3()!!) } }
    }

    /** One number of every item that has it. */
    fun number(key: String): Map<String, Double> =
        db.select(ITEM_NUMBER.ITEM_ID, ITEM_NUMBER.CONTENT).from(ITEM_NUMBER).where(ITEM_NUMBER.NUMBER_KEY.eq(key))
            .fetch().associate { it.value1()!! to it.value2()!! }

    // --- Texts ---

    fun setTexts(itemId: String, texts: Map<String, String?>) {
        if (texts.isEmpty()) return
        val before = texts(itemId)
        var any = false
        texts.forEach { (key, value) ->
            val text = value?.trim()?.takeIf { it.isNotEmpty() }
            if (before[key] == text) return@forEach
            any = true
            if (text == null) {
                db.deleteFrom(ITEM_TEXT).where(ITEM_TEXT.ITEM_ID.eq(itemId), ITEM_TEXT.TEXT_KEY.eq(key)).execute()
            } else {
                db.insertInto(ITEM_TEXT).set(ITEM_TEXT.ITEM_ID, itemId).set(ITEM_TEXT.TEXT_KEY, key).set(ITEM_TEXT.CONTENT, text)
                    .onDuplicateKeyUpdate().set(ITEM_TEXT.CONTENT, text)
                    .execute()
            }
        }
        if (any) changed(listOf(itemId))
    }

    fun texts(itemId: String): Map<String, String> =
        db.select(ITEM_TEXT.TEXT_KEY, ITEM_TEXT.CONTENT).from(ITEM_TEXT).where(ITEM_TEXT.ITEM_ID.eq(itemId))
            .fetch().associate { it.value1()!! to it.value2()!! }

    fun textsOf(ids: Collection<String>, keys: Collection<String>? = null): Map<String, Map<String, String>> {
        val out = HashMap<String, MutableMap<String, String>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(ITEM_TEXT.ITEM_ID, ITEM_TEXT.TEXT_KEY, ITEM_TEXT.CONTENT).from(ITEM_TEXT)
                .where(ITEM_TEXT.ITEM_ID.`in`(chunk))
                .and(keys?.let { ITEM_TEXT.TEXT_KEY.`in`(it) } ?: DSL.noCondition())
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = it.value3()!! }
        }
        return out
    }

    /** Every text of the [keys] of every item, streamed item by item. */
    /** The title of every item as the site gives it: item to title. */
    fun titles(): Map<String, String> =
        db.select(ITEM.ITEM_ID, ITEM.TITLE).from(ITEM).fetchSize(5000).fetchMap({ it.value1()!! }, { it.value2()!! })

    fun forEachTexts(keys: Collection<String>, action: (itemId: String, texts: Map<String, String>) -> Unit) {
        var current: String? = null
        val texts = HashMap<String, String>()
        db.select(ITEM_TEXT.ITEM_ID, ITEM_TEXT.TEXT_KEY, ITEM_TEXT.CONTENT).from(ITEM_TEXT)
            .where(ITEM_TEXT.TEXT_KEY.`in`(keys))
            .orderBy(ITEM_TEXT.ITEM_ID)
            .fetchSize(500).fetchLazy().use { cursor ->
                cursor.forEach {
                    if (it.value1() != current) {
                        current?.let { id -> action(id, texts.toMap()) }
                        texts.clear()
                        current = it.value1()
                    }
                    texts[it.value2()!!] = it.value3()!!
                }
            }
        current?.let { action(it, texts.toMap()) }
    }

    companion object {
        private const val BATCH = 1000

        private val HEAD = listOf(ITEM.ITEM_ID, ITEM.URL, ITEM.TITLE, ITEM.VERSION, ITEM.UPDATED_AT, ITEM.FIRST_SEEN_AT)

        private fun head(r: Record) = StoredItem(
            id = r.get(ITEM.ITEM_ID)!!,
            url = r.get(ITEM.URL)!!,
            title = r.get(ITEM.TITLE)!!,
            version = r.get(ITEM.VERSION)!!,
            updatedAt = r.get(ITEM.UPDATED_AT)!!,
            firstSeenAt = r.get(ITEM.FIRST_SEEN_AT)!!,
        )
    }
}

/** What the list sorts and filters by. */
data class ItemKeys(val id: String, val version: String, val updatedAt: Instant)
