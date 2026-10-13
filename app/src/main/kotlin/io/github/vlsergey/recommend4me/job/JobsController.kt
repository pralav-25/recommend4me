package io.github.vlsergey.recommend4me.job

import io.github.vlsergey.recommend4me.api.JobsApi
import io.github.vlsergey.recommend4me.api.model.JobRequest
import io.github.vlsergey.recommend4me.api.model.CatalogueProgress
import io.github.vlsergey.recommend4me.api.model.JobStatus
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.job
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import io.github.vlsergey.recommend4me.api.model.JobState as ApiJobState
import io.github.vlsergey.recommend4me.api.model.SourceMode as ApiSourceMode

@RestController
class JobsController(private val jobs: Jobs, private val stores: Stores, private val contexts: SourceContexts) : JobsApi {

    override fun listJobs(): ResponseEntity<List<JobStatus>> =
        ResponseEntity.ok(stores.sources.filter { s -> s.source.modes.any { it.job } }.map { status(it.id) })

    override fun startJob(source: String, jobRequest: JobRequest): ResponseEntity<JobStatus> {
        val started = jobs.start(source, SourceMode.valueOf(jobRequest.mode.name))
        return ResponseEntity.status(if (started) HttpStatus.ACCEPTED else HttpStatus.CONFLICT).body(status(source))
    }

    override fun cancelJob(source: String): ResponseEntity<JobStatus> {
        jobs.cancel(source)
        return ResponseEntity.ok(status(source))
    }

    private fun status(source: String): JobStatus {
        val p = jobs.status(source)
        return JobStatus(
            source = source,
            state = ApiJobState.valueOf(p.state.name),
            processed = p.processed,
            total = p.total,
            newItems = p.newItems,
            errors = p.errors,
            items = stores.source(source)?.items?.count() ?: 0,
            mode = p.mode?.let { ApiSourceMode.valueOf(it.name) },
            resumable = jobs.resumable(source)?.let { ApiSourceMode.valueOf(it.name) },
            phase = p.phase,
            phaseStartedAt = p.phaseStartedAt?.atOffset(ZoneOffset.UTC),
            messageKey = p.messageKey,
            failure = p.failure,
            loggedIn = p.loggedIn,
            startedAt = p.startedAt?.atOffset(ZoneOffset.UTC),
            finishedAt = p.finishedAt?.atOffset(ZoneOffset.UTC),
            catalogue = catalogue(source),
        )
    }

    /** How far the whole catalogue is loaded, as the source tells it. */
    private fun catalogue(source: String): CatalogueProgress? {
        val store = stores.source(source) ?: return null
        if (SourceMode.SCRAPE_ALL !in store.source.modes) return null
        return store.source.scrapeProgress(contexts.of(source))?.let { CatalogueProgress(it.stage, it.done, it.total, it.complete) }
    }
}
