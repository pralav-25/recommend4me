package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(ItemRemoval::class.java)

/**
 * A WORK REMOVED FROM THE LIBRARY — gone from the site — with everything of it in every database:
 * the source's (the work, its texts, pictures, reviews, parts and every vector of them, the pages
 * kept of it alone), the files of its pictures, the user's answers and links, grades, marks and the
 * site's signals, and what the model made of it (predictions, chances, likeness). Nothing is kept
 * to tell it was there: seen on the site again — removed by mistake — it is made anew, as a work
 * never seen.
 *
 * What is learnt of all the works together — the model, the directions of the sets — is made again
 * without it: the change is told as any other.
 */
@Component
class ItemRemoval(private val stores: Stores, private val recommendations: Recommendations, private val events: ApplicationEventPublisher) {

    /** False when there is no such work. */
    fun remove(key: ItemKey): Boolean {
        val store = stores.source(key.source) ?: return false
        if (!store.items.exists(key.id)) return false
        val type = stores.typeOf(key.source)
        val graded = store.ratings.ofItem(key.id).isNotEmpty()
        // The files of its pictures first: their names are in the rows that go with the work
        store.files.delete(store.pictures.sync(key.id, emptyList()))
        store.items.remove(key.id)
        store.corrections.forget(key.id)
        store.ratings.forget(key.id)
        store.signals.forget(key.id)
        store.suggestions.forget(key.id)
        // A word on a match of a mark of any source may name it
        type.sources.forEach { it.marks.forget(key) }
        type.links.forget(key)
        type.models.forget(key)
        type.likeness.forget(key)
        type.workLikeness.forget(key)
        log.info("{}: {} removed from the library, with everything of it", key.source, key.id)
        events.publishEvent(ItemsChanged(key.source, listOf(key.id)))
        if (graded) events.publishEvent(GradesChanged(type.id, key.source, key.id))
        recommendations.scheduleRetrain(type.id)
        return true
    }
}
