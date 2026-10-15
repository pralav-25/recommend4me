package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_LINK
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import java.time.Instant

/**
 * Items of different sources of one content type that the user said are one work: a link kept in the
 * corrections of the source of the smaller of the two keys, its item first.
 */
class TypeLinkRepository(
    /** The corrections of every source of the type, by source. */
    private val corrections: Map<String, DSLContext>,
) {

    private fun of(source: String): DSLContext = corrections[source] ?: error("No such source: $source")

    fun link(a: ItemKey, b: ItemKey, now: Instant) {
        val (first, second) = if (a.toString() < b.toString()) a to b else b to a
        of(first.source).insertInto(ITEM_LINK)
            .set(ITEM_LINK.ITEM_ID, first.id).set(ITEM_LINK.OTHER_SOURCE, second.source).set(ITEM_LINK.OTHER_ITEM_ID, second.id)
            .set(ITEM_LINK.LINKED_AT, now)
            .onDuplicateKeyIgnore().execute()
    }

    fun unlink(a: ItemKey, b: ItemKey) {
        fun drop(x: ItemKey, y: ItemKey) = corrections[x.source]?.deleteFrom(ITEM_LINK)
            ?.where(ITEM_LINK.ITEM_ID.eq(x.id), ITEM_LINK.OTHER_SOURCE.eq(y.source), ITEM_LINK.OTHER_ITEM_ID.eq(y.id))?.execute()
        drop(a, b)
        drop(b, a)
    }

    /** Every link across the sources, read source by source. */
    fun links(): List<Pair<ItemKey, ItemKey>> = corrections.flatMap { (source, db) ->
        db.select(ITEM_LINK.ITEM_ID, ITEM_LINK.OTHER_SOURCE, ITEM_LINK.OTHER_ITEM_ID).from(ITEM_LINK).where(ITEM_LINK.OTHER_SOURCE.ne(source))
            .fetch { ItemKey(source, it.value1()!!) to ItemKey(it.value2()!!, it.value3()!!) }
    }

    /** The links of the item removed from the library, on either side. */
    fun forget(key: ItemKey) {
        corrections.forEach { (source, db) ->
            val mine = if (source == key.source) ITEM_LINK.ITEM_ID.eq(key.id).and(ITEM_LINK.OTHER_SOURCE.ne(source)) else null
            val theirs = ITEM_LINK.OTHER_SOURCE.eq(key.source).and(ITEM_LINK.OTHER_ITEM_ID.eq(key.id)).and(ITEM_LINK.OTHER_SOURCE.ne(source))
            db.deleteFrom(ITEM_LINK).where(if (mine != null) mine.or(theirs) else theirs).execute()
        }
    }
}
