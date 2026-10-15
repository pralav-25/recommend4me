package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.model.MeasuringState
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger(FacetComparisons::class.java)

/** One suggester in a comparison of a facet: its state, and once fitted what it tried and chose. Its title is the interface's word "<method>:title". */
class MethodMeasure(val method: String) {
    @Volatile var state = MeasuringState.WAITING
    @Volatile var report: FitReport? = null
    @Volatile var error: String? = null
    @Volatile var millis: Long? = null
}

/** A comparison of every suggester on one facet of one source: under way until [finishedAt]. */
class FacetComparison(val startedAt: Instant, val methods: List<MethodMeasure>) {
    @Volatile var items = 0
    @Volatile var finishedAt: Instant? = null
}

/**
 * EVERY SUGGESTER FITTED ON ONE FACET FROM SCRATCH, IN THE BACKGROUND, one after another: each
 * judged on the same works held out by the same folds ([SuggestionPairs]), so their losses compare;
 * each one's result there as soon as it is fitted. Nothing is kept but the comparison of every facet
 * last started, while the application runs: it is a measuring, not data — the facet goes on with
 * its method till the user chooses another. A comparison under way is not started again.
 */
@Component
class FacetComparisons(private val suggestions: FacetSuggestions, private val plugins: Plugins, private val workload: Workload) {
    private val comparisons = ConcurrentHashMap<String, FacetComparison>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "facet-comparison").apply { isDaemon = true } }

    fun of(store: SourceStore, facet: String): FacetComparison? = comparisons[keyOf(store, facet)]

    fun start(store: SourceStore, facet: String): FacetComparison = comparisons.compute(keyOf(store, facet)) { _, running ->
        if (running != null && running.finishedAt == null) return@compute running
        FacetComparison(Instant.now(), plugins.suggesters.map { MethodMeasure(it.id) }).also { c ->
            executor.submit { workload.run(Work("methodsCompared", source = store.id, facet = facet), mapOf("asked" to 1)) { run(store, facet, c) } }
        }
    }!!

    private fun keyOf(store: SourceStore, facet: String) = "${store.id}/$facet"

    private fun run(store: SourceStore, facet: String, comparison: FacetComparison) {
        try {
            val task = suggestions.task(store, facet)
            if (task == null) {
                comparison.methods.forEach { it.state = MeasuringState.DONE }
                return
            }
            comparison.items = task.itemCount
            comparison.methods.forEach { measure ->
                val suggester = plugins.suggesters.firstOrNull { it.id == measure.method }
                if (suggester == null) {
                    // Measured in the background, outside any request: told in English
                    measure.error = "The method is gone"
                    measure.state = MeasuringState.FAILED
                    return@forEach
                }
                measure.state = MeasuringState.MEASURING
                val started = System.currentTimeMillis()
                try {
                    measure.report = suggester.fit(task, null)?.report
                    measure.state = MeasuringState.DONE
                } catch (e: Exception) {
                    log.warn("{}: {} could not be fitted on {}", store.id, suggester.id, facet, e)
                    measure.error = e.message ?: e.javaClass.simpleName
                    measure.state = MeasuringState.FAILED
                }
                measure.millis = System.currentTimeMillis() - started
                log.info("{}: {} fitted on {} of {} items in {} ms: {}", store.id, suggester.id, facet, task.itemCount, measure.millis, measure.report?.loss)
            }
        } catch (e: Exception) {
            log.warn("{}: the methods of {} could not be compared", store.id, facet, e)
            comparison.methods.filter { it.state != MeasuringState.DONE }.forEach {
                it.error = e.message ?: e.javaClass.simpleName
                it.state = MeasuringState.FAILED
            }
        } finally {
            comparison.finishedAt = Instant.now()
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
