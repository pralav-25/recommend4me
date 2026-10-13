package io.github.vlsergey.recommend4me.job

import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.Job
import io.github.vlsergey.recommend4me.source.JobCancelled
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.job
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger(Jobs::class.java)

enum class JobState { IDLE, RUNNING, CANCELLING, DONE, CANCELLED, FAILED }

data class JobProgress(
    val state: JobState = JobState.IDLE,
    val mode: SourceMode? = null,
    val phase: String? = null,
    val phaseStartedAt: Instant? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val newItems: Int = 0,
    val errors: Int = 0,
    val message: String? = null,
    val loggedIn: Boolean? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
)

/**
 * The scraping jobs of the sources, one at a time per source, each on a thread of its own: the
 * source's own work, then the vectors of the texts it wrote and a training of the content type.
 *
 * A JOB OF THE WHOLE CATALOGUE GOES ON AFTER A RESTART: it is remembered as running
 * (`job.<source>.running`) until it ends or the user stops it, and started again on the next start.
 */
@Service
class Jobs(
    private val stores: Stores,
    private val contexts: SourceContexts,
    private val settings: Settings,
    private val textVectors: TextVectors,
    private val recommendations: Recommendations,
) {
    private val runs = ConcurrentHashMap<String, Run>()
    private val executors = ConcurrentHashMap<String, ExecutorService>()

    @Volatile
    private var shuttingDown = false

    /** A job's state as the interface sees it, and the plugin's handle on it. */
    private inner class Run(val sourceId: String, val mode: SourceMode) : Job {
        @Volatile
        var progress = JobProgress(state = JobState.RUNNING, mode = mode, startedAt = Instant.now())

        @Volatile
        var cancelRequested = false

        @Synchronized
        fun update(change: (JobProgress) -> JobProgress) {
            progress = change(progress)
        }

        override fun phase(name: String, total: Int, processed: Int) =
            update { it.copy(phase = name, phaseStartedAt = Instant.now(), processed = processed, total = total) }

        override fun progress(processed: Int, total: Int?) = update { it.copy(processed = processed, total = total ?: it.total) }
        override fun found(newItems: Int) = update { it.copy(newItems = it.newItems + newItems) }
        override fun error(message: String) {
            log.warn("{}: {}", sourceId, message)
            update { it.copy(errors = it.errors + 1) }
        }
        override fun message(text: String?) = update { it.copy(message = text) }
        override fun session(loggedIn: Boolean?) = update { it.copy(loggedIn = loggedIn) }
        override val cancelled: Boolean get() = cancelRequested

        override fun checkpoint() {
            try {
                val store = stores.source(sourceId) ?: return
                textVectors.refresh(store, cancelled = { cancelRequested })
                recommendations.retrainAndScore(store.type)
            } catch (e: Exception) {
                log.warn("{}: checkpoint failed: {}", sourceId, e.message)
            }
        }

        override fun state(key: String): String? = settings.get("job.$sourceId.$key")
        override fun state(key: String, value: String?) = settings.set("job.$sourceId.$key", value)
    }

    fun status(sourceId: String): JobProgress = runs[sourceId]?.progress ?: JobProgress()

    /** The mode a job of the source left running at the last stop of the application. */
    fun resumable(sourceId: String): SourceMode? = settings.get("job.$sourceId.running")?.let { runCatching { SourceMode.valueOf(it) }.getOrNull() }

    /** Starts a job; false when one of the source runs already or the source has no such mode. */
    @Synchronized
    fun start(sourceId: String, mode: SourceMode): Boolean {
        val store = stores.source(sourceId) ?: return false
        if (!mode.job || mode !in store.source.modes) return false
        val current = runs[sourceId]?.progress?.state
        if (current == JobState.RUNNING || current == JobState.CANCELLING) return false
        val run = Run(sourceId, mode)
        runs[sourceId] = run
        val executor = executors.computeIfAbsent(sourceId) { Executors.newSingleThreadExecutor { r -> Thread(r, "job-$sourceId").apply { isDaemon = true } } }
        executor.execute { run(run) }
        return true
    }

    @Synchronized
    fun cancel(sourceId: String) {
        val run = runs[sourceId] ?: return
        if (run.progress.state == JobState.RUNNING) {
            run.cancelRequested = true
            run.update { it.copy(state = JobState.CANCELLING) }
        }
    }

    private fun run(run: Run) {
        val store = stores.source(run.sourceId) ?: return
        val whole = run.mode == SourceMode.SCRAPE_ALL
        if (whole) settings.set("job.${run.sourceId}.running", run.mode.name)
        try {
            store.source.scrape(run.mode, contexts.of(store.id), run)
            run.checkCancelled()
            run.phase("vectors")
            textVectors.refresh(store, progress = { done, total -> run.progress(done, total) }, cancelled = { run.cancelRequested })
            run.checkCancelled()
            run.phase("scoring")
            recommendations.retrainAndScore(store.type)
            if (whole) settings.set("job.${run.sourceId}.running", null)
            run.update { it.copy(state = JobState.DONE, finishedAt = Instant.now()) }
        } catch (_: JobCancelled) {
            // Stopped by the user: it waits for the user to resume it
            if (whole && !shuttingDown) settings.set("job.${run.sourceId}.running", null)
            if (!shuttingDown) recommendations.scoreAll(store.type)
            run.update { it.copy(state = JobState.CANCELLED, finishedAt = Instant.now()) }
        } catch (e: Exception) {
            log.error("{}: the job failed", run.sourceId, e)
            run.update { it.copy(state = JobState.FAILED, message = e.message ?: e.javaClass.simpleName, finishedAt = Instant.now()) }
        }
    }

    /** A job of the whole catalogue the last run of the application left unfinished goes on. */
    @Order(10)
    @EventListener(ApplicationReadyEvent::class)
    fun resume() {
        stores.sources.forEach { s ->
            resumable(s.id)?.let { mode ->
                log.info("{}: resuming the job {}", s.id, mode)
                start(s.id, mode)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        shuttingDown = true
        runs.values.forEach { it.cancelRequested = true }
        executors.values.forEach { it.shutdownNow() }
    }
}
