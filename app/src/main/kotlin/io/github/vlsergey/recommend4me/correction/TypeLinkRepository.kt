package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.ITEM_LINK
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** Items of different sources of one content type that the user said are one work. */
class TypeLinkRepository(private val db: DSLContext) {

    fun link(a: ItemKey, b: ItemKey, now: Instant) {
        val (first, second) = if (a.toString() < b.toString()) a to b else b to a
        db.insertInto(ITEM_LINK)
            .set(ITEM_LINK.SOURCE, first.source).set(ITEM_LINK.ITEM_ID, first.id)
            .set(ITEM_LINK.OTHER_SOURCE, second.source).set(ITEM_LINK.OTHER_ITEM_ID, second.id)
            .set(ITEM_LINK.LINKED_AT, now)
            .onDuplicateKeyIgnore().execute()
    }

    fun unlink(a: ItemKey, b: ItemKey) {
        fun pair(x: ItemKey, y: ItemKey) = ITEM_LINK.SOURCE.eq(x.source).and(ITEM_LINK.ITEM_ID.eq(x.id))
            .and(ITEM_LINK.OTHER_SOURCE.eq(y.source)).and(ITEM_LINK.OTHER_ITEM_ID.eq(y.id))
        db.deleteFrom(ITEM_LINK).where(DSL.or(pair(a, b), pair(b, a))).execute()
    }

    fun links(): List<Pair<ItemKey, ItemKey>> =
        db.select(ITEM_LINK.SOURCE, ITEM_LINK.ITEM_ID, ITEM_LINK.OTHER_SOURCE, ITEM_LINK.OTHER_ITEM_ID).from(ITEM_LINK)
            .fetch { ItemKey(it.value1()!!, it.value2()!!) to ItemKey(it.value3()!!, it.value4()!!) }

    /** The links of the item removed from the library, on either side. */
    fun forget(key: ItemKey) {
        db.deleteFrom(ITEM_LINK).where(
            ITEM_LINK.SOURCE.eq(key.source).and(ITEM_LINK.ITEM_ID.eq(key.id))
                .or(ITEM_LINK.OTHER_SOURCE.eq(key.source).and(ITEM_LINK.OTHER_ITEM_ID.eq(key.id))),
        ).execute()
    }
}
