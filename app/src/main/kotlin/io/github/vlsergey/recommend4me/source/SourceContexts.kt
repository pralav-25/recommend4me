package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.part.PartsSaved
import io.github.vlsergey.recommend4me.picture.PicturesListed
import io.github.vlsergey.recommend4me.settings.Settings
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Instant

private val log = LoggerFactory.getLogger(SourceContexts::class.java)

/** The [SourceContext] the application hands each source: its stores behind the plugin API. */
@Component
class SourceContexts(private val stores: Stores, private val settings: Settings, private val events: ApplicationEventPublisher) {

    fun of(sourceId: String): SourceContext {
        val store = stores.source(sourceId) ?: error("No such source: $sourceId")
        return Context(store)
    }

    private inner class Context(private val store: SourceStore) : SourceContext {
        override val sourceId: String get() = store.id
        override val items: ItemStore = Items(store, events)
        override val database: DSLContext get() = store.sourceDsl
        override val settings: SourceSettings = this@SourceContexts.settings.of(store.id)
        override val signals: SiteSignals get() = store.signals
        override val folder: Path get() = store.folder

        override fun keepPage(url: String, html: String, itemId: String?) =
            store.pages.keep(url, html, itemId, Instant.now(), store.source.parserVersion)

        override fun gradedItems(): Set<String> = store.ratings.gradedItems()

        override fun jobState(key: String): String? = this@SourceContexts.settings.get("job.${store.id}.$key")

        override fun itemsInOrder(): List<String> {
            val graded = store.ratings.gradedItems()
            val predictions = stores.typeOf(store.id).models.predictions(store.id)
            return store.items.keys()
                .sortedWith(
                    compareByDescending<io.github.vlsergey.recommend4me.item.ItemKeys> { it.id in graded }
                        .thenByDescending { predictions[it.id] ?: Double.NEGATIVE_INFINITY }
                        .thenByDescending { it.updatedAt },
                )
                .map { it.id }
        }
    }

    private class Items(private val store: SourceStore, private val events: ApplicationEventPublisher) : ItemStore {
        override fun upsert(item: ItemHead, now: Instant): Boolean = store.items.upsert(item, now)
        override fun find(itemId: String): StoredItem? = store.items.find(itemId)
        override fun count(): Int = store.items.count()
        override fun latestUpdate(): Instant? = store.items.latestUpdate()
        override fun setFacet(itemId: String, facet: String, values: List<FacetValue>) = store.items.setFacet(itemId, facet, standardised(itemId, facet, values))

        override fun nameFacetValues(facet: String, names: Map<String, String>) {
            val standard = store.schema.facet(facet)?.standard
            store.items.nameFacetValues(facet, if (standard?.values == null) names else names.mapValues { (key, name) -> standard.value(key)?.label ?: name })
        }

        /**
         * The values of a standard facet with a closed list of them ([StandardFacet][io.github.vlsergey.recommend4me.contenttype.StandardFacet])
         * named as the standard names them; a value outside the list — one the source could not
         * map — is left out and told of in the log. Of any other facet the values as they are.
         */
        private fun standardised(itemId: String, facet: String, values: List<FacetValue>): List<FacetValue> {
            val standard = store.schema.facet(facet)?.standard?.takeIf { it.values != null } ?: return values
            return values.mapNotNull { v ->
                val known = standard.value(v.key)
                if (known == null) log.warn("{}: «{}» of item {} is no value of the standard facet {}: left out", store.id, v.name ?: v.key, itemId, standard.key)
                known?.let { FacetValue(it.key, it.label) }
            }
        }
        override fun facets(itemId: String): Map<String, List<String>> = store.items.facets(itemId)
        override fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit) = store.items.forEachFacet(facet, action)
        override fun setNumbers(itemId: String, numbers: Map<String, Double?>) = store.items.setNumbers(itemId, numbers)
        override fun setTexts(itemId: String, texts: Map<String, String?>) = store.items.setTexts(itemId, texts)
        override fun texts(itemId: String): Map<String, String> = store.items.texts(itemId)
        override fun setPictures(itemId: String, urls: List<String?>) {
            store.files.delete(store.pictures.sync(itemId, urls))
            events.publishEvent(PicturesListed(store.id))
        }
        override fun saveReviews(itemId: String, reviews: List<ReviewData>) = store.reviews.save(itemId, reviews)
        override fun reviewIds(itemId: String): Set<String> = store.reviews.ids(itemId)
        override fun reviewContents(itemId: String): Map<String, String> = store.reviews.ofItem(itemId).associate { it.reviewId to it.content }
        override fun saveParts(itemId: String, parts: List<PartData>) {
            store.parts.save(itemId, parts)
            if (parts.any { it.content != null }) events.publishEvent(PartsSaved(store.id))
        }
        override fun parts(itemId: String): Map<String, Boolean> = store.parts.ofItem(itemId).associate { it.partId to it.hasText }
    }
}
