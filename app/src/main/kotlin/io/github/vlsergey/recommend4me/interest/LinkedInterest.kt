package io.github.vlsergey.recommend4me.interest

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(LinkedInterest::class.java)

/**
 * THE ITEMS OF ONE WORK HAVE ONE INTEREST MARK, WRITTEN TO EACH — as their grade
 * ([LinkedGrades][io.github.vlsergey.recommend4me.rating.LinkedGrades]): the newest mark of the
 * work's items goes to every other; a link taken back leaves them their marks.
 */
@Component
class LinkedInterest(private val stores: Stores, private val works: Works, private val events: ApplicationEventPublisher) {

    /** The work [key] is of given the newest mark of its items, at every item that has another or none. */
    fun share(key: ItemKey) = share(works.of(stores.typeOf(key.source)).members(key))

    /** The mark of every other item of the work [key] is of taken back too: the user took back its own. */
    fun takeBack(key: ItemKey) {
        works.of(stores.typeOf(key.source)).members(key).drop(1).forEach { m ->
            val store = stores.source(m.source) ?: return@forEach
            if (store.interest.ofItem(m.id) == null) return@forEach
            store.interest.forget(m.id)
            events.publishEvent(InterestChanged(store.type, m.source, m.id))
        }
    }

    /** Every work linked before its marks were shared, once on start. */
    @Order(0)
    @EventListener(ApplicationReadyEvent::class)
    fun shareAll() {
        stores.types.forEach { type -> works.of(type).linkedWorks().forEach(::share) }
    }

    private fun share(members: List<ItemKey>) {
        if (members.size < 2) return
        val marks = members.mapNotNull { m -> stores.source(m.source)?.let { s -> Triple(m, s, s.interest.ofItem(m.id)) } }
        val newest = marks.mapNotNull { it.third }.maxByOrNull { it.markedAt } ?: return
        marks.forEach { (m, store, mark) ->
            if (mark?.interesting == newest.interesting) return@forEach
            // At the time of the mark shared: the newest of the work stays the newest
            store.interest.mark(m.id, newest.interesting, newest.markedAt)
            log.info("{}: {} marked {} as the item it is linked to", m.source, m.id, if (newest.interesting) "interesting" else "not interesting")
            events.publishEvent(InterestChanged(store.type, m.source, m.id))
        }
    }
}
