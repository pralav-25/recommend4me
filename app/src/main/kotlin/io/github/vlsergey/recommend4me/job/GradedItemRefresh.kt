package io.github.vlsergey.recommend4me.job

import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger(GradedItemRefresh::class.java)

/**
 * The page of a work the user has just graded, downloaded at once when its source can and the work
 * lacks its details — not when a scrape reaches it hours later. A graded work without its texts
 * teaches the model nothing about texts, and worse, teaches it that missing texts mean a grade.
 */
@Service
class GradedItemRefresh(
    private val stores: Stores,
    private val contexts: SourceContexts,
    private val textVectors: TextVectors,
    private val recommendations: Recommendations,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "graded-refresh").apply { isDaemon = true } }

    @EventListener
    fun gradesChanged(event: GradesChanged) {
        val store = stores.source(event.source) ?: return
        val context = contexts.of(store.id)
        if (!store.source.needsRefresh(event.itemId, context)) return
        executor.execute {
            try {
                if (store.source.refresh(event.itemId, context)) {
                    textVectors.refresh(store, "gradedPage", only = listOf(event.itemId))
                    recommendations.scheduleRetrain(store.type, "gradedPage", byUser = false)
                }
            } catch (e: Exception) {
                log.warn("{}: the page of the graded item {} was not loaded: {}", store.id, event.itemId, e.message)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
