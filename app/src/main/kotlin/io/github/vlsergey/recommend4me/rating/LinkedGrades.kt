package io.github.vlsergey.recommend4me.rating

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(LinkedGrades::class.java)

/**
 * THE ITEMS OF ONE WORK HAVE ONE GRADE, WRITTEN TO EACH: the items the user linked — the same work
 * on two sites, or twice on one — every one of them graded in its own database, at its own current
 * version, as if graded by hand. Nothing is borrowed from a linked item when shown, listed or
 * learnt from: each has its own grade. A link taken back leaves them their grades.
 *
 * The grade shared is the newest of the items' current grades: linked, two graded otherwise take
 * the last word of the user; graded or taken back after, it goes to every item of the work.
 */
@Component
class LinkedGrades(private val stores: Stores, private val works: Works, private val events: ApplicationEventPublisher) {

    /** The work [key] is of given the newest grade of its items' current ones, at every item that has another or none. */
    fun share(key: ItemKey) = share(works.of(stores.typeOf(key.source)).members(key))

    /** The grade of the current version of every other item of the work [key] is of taken back too: the user took back its own. */
    fun takeBack(key: ItemKey) {
        works.of(stores.typeOf(key.source)).members(key).drop(1).forEach { m ->
            val (store, version) = current(m) ?: return@forEach
            if (store.ratings.ofItem(m.id).none { it.version == version }) return@forEach
            store.ratings.unrate(m.id, version)
            events.publishEvent(GradesChanged(store.type, m.source, m.id))
        }
    }

    /** Every work linked before its grades were shared — and any a grade of went astray — shared, once on start. */
    @Order(0)
    @EventListener(ApplicationReadyEvent::class)
    fun shareAll() {
        stores.types.forEach { type -> works.of(type).linkedWorks().forEach(::share) }
    }

    private fun share(members: List<ItemKey>) {
        if (members.size < 2) return
        val current = members.mapNotNull { m -> current(m)?.let { m to it } }
        val newest = current.flatMap { (m, at) -> at.first.ratings.ofItem(m.id).filter { it.version == at.second } }.maxByOrNull { it.ratedAt } ?: return
        current.forEach { (m, at) ->
            val (store, version) = at
            if (store.ratings.ofItem(m.id).any { it.version == version && it.grade == newest.grade }) return@forEach
            // At the time of the grade shared: the newest of the work stays the newest
            store.ratings.rate(m.id, version, newest.grade, newest.ratedAt)
            log.info("{}: {} graded {} as the item it is linked to", m.source, m.id, newest.grade)
            events.publishEvent(GradesChanged(store.type, m.source, m.id))
        }
    }

    /** The item's source and its current version; null for an item no longer there. */
    private fun current(key: ItemKey): Pair<SourceStore, String>? {
        val store = stores.source(key.source) ?: return null
        val version = store.items.find(key.id)?.version ?: return null
        return store to version
    }
}
