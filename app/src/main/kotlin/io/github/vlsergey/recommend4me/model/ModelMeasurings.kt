package io.github.vlsergey.recommend4me.model

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger(ModelMeasurings::class.java)

/** A measuring of a model asked for: under way until [finishedAt]; its [result], none when there were too few grades or it failed. */
class Measuring<T : Any>(val startedAt: Instant) {
    @Volatile var result: T? = null
    @Volatile var finishedAt: Instant? = null
}

/**
 * THE MEASURINGS OF A MODEL ASKED FOR — what every part of the input is worth
 * ([Recommendations.worth]), whether weighing the parts would rank better ([Recommendations.weighing])
 * — IN THE BACKGROUND: each takes from a dozen trainings to hundreds, in the queue of the
 * computations, and the request that asks does not wait for them ([io.github.vlsergey.recommend4me.workload.Requests]).
 * Nothing is kept but the measuring of every type last started, while the application runs: it is
 * a measuring, not data. A measuring under way is not started again.
 */
@Component
class ModelMeasurings(private val recommendations: Recommendations) {
    private val worth = ConcurrentHashMap<String, Measuring<ModelWorth>>()
    private val weighing = ConcurrentHashMap<String, Measuring<Weighing>>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "model-measuring").apply { isDaemon = true } }

    fun worthOf(typeId: String): Measuring<ModelWorth>? = worth[typeId]

    fun weighingOf(typeId: String): Measuring<Weighing>? = weighing[typeId]

    fun measureWorth(typeId: String): Measuring<ModelWorth> = start(worth, typeId, "part worth") { recommendations.worth(typeId) }

    fun measureWeighing(typeId: String, cautious: Boolean): Measuring<Weighing> = start(weighing, typeId, "weighing") { recommendations.weighing(typeId, cautious) }

    private fun <T : Any> start(of: ConcurrentHashMap<String, Measuring<T>>, typeId: String, what: String, body: () -> T?): Measuring<T> =
        of.compute(typeId) { _, running ->
            if (running != null && running.finishedAt == null) return@compute running
            Measuring<T>(Instant.now()).also { m ->
                executor.submit {
                    try {
                        m.result = body()
                    } catch (e: Exception) {
                        log.warn("{}: the {} could not be measured", typeId, what, e)
                    } finally {
                        m.finishedAt = Instant.now()
                    }
                }
            }
        }!!

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
