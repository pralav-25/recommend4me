package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger(ScorerComparisons::class.java)

/** Where a scorer's measuring stands. */
enum class MeasuringState { WAITING, MEASURING, DONE, FAILED }

/** One scorer in a comparison: its state, and its result once measured. Its title is the interface's word "<scorer>:title". */
class ScorerMeasure(val scorer: String) {
    @Volatile var state = MeasuringState.WAITING
    @Volatile var chosen: RankingResult? = null
    @Volatile var candidates: List<RankingResult> = emptyList()
    @Volatile var error: String? = null
    @Volatile var millis: Long? = null
}

/** A comparison of every scorer of a type on its grades — or its marks of interest: under way until [finishedAt]. */
class ScorerComparison(val startedAt: Instant, val scorers: List<ScorerMeasure>) {
    @Volatile var grades = 0
    @Volatile var finishedAt: Instant? = null
}

/**
 * EVERY SCORER CROSS-VALIDATED ON THE CURRENT GRADES, IN THE BACKGROUND: the scorers one after
 * another, each one's result there as soon as it is measured — a comparison of five scorers takes
 * a minute, and the user sees the first ones while the rest are measured. Nothing is kept but the
 * comparison of every type last started, while the application runs: it is a measuring, not data.
 * A comparison under way is not started again.
 */
@Component
class ScorerComparisons(private val recommendations: Recommendations, private val plugins: Plugins, private val workload: Workload) {
    private val comparisons = ConcurrentHashMap<String, ScorerComparison>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "scorer-comparison").apply { isDaemon = true } }

    /** The comparison last started of the type's model of grades — of its model of [interest]. */
    fun of(typeId: String, interest: Boolean = false): ScorerComparison? = comparisons[keyOf(typeId, interest)]

    fun start(typeId: String, interest: Boolean = false): ScorerComparison = comparisons.compute(keyOf(typeId, interest)) { _, running ->
        if (running != null && running.finishedAt == null) return@compute running
        ScorerComparison(Instant.now(), plugins.scorers.map { ScorerMeasure(it.id) }).also { c ->
            executor.submit { workload.run("$typeId: the scorers compared") { run(typeId, interest, c) } }
        }
    }!!

    private fun keyOf(typeId: String, interest: Boolean) = if (interest) "$typeId/interest" else typeId

    private fun run(typeId: String, interest: Boolean, comparison: ScorerComparison) {
        try {
            val input = recommendations.measuringInput(typeId, interest)
            if (input == null) {
                comparison.scorers.forEach { it.state = MeasuringState.DONE }
                return
            }
            comparison.grades = input.grades
            comparison.scorers.forEach { measure ->
                val scorer = plugins.scorers.firstOrNull { it.id == measure.scorer }
                if (scorer == null) {
                    // Measured in the background, outside any request: told in English
                    measure.error = "The scorer is gone"
                    measure.state = MeasuringState.FAILED
                    return@forEach
                }
                measure.state = MeasuringState.MEASURING
                val started = System.currentTimeMillis()
                try {
                    val candidates = CrossValidation.measureAll(input.task, scorer, input.xFor)
                    measure.candidates = candidates.map { it.result() }
                    measure.chosen = CrossValidation.choose(candidates)?.result()
                    measure.state = MeasuringState.DONE
                } catch (e: Exception) {
                    log.warn("{}: the scorer {} could not be measured", typeId, scorer.id, e)
                    measure.error = e.message ?: e.javaClass.simpleName
                    measure.state = MeasuringState.FAILED
                }
                measure.millis = System.currentTimeMillis() - started
                log.info("{}: {} measured on {} grades in {} ms: {}", typeId, scorer.id, input.grades, measure.millis, measure.chosen?.metrics?.concordance)
            }
        } catch (e: Exception) {
            log.warn("{}: the scorers could not be compared", typeId, e)
            comparison.scorers.filter { it.state != MeasuringState.DONE }.forEach {
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
