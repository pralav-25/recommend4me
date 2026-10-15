package io.github.vlsergey.recommend4me.workload

import io.github.vlsergey.recommend4me.api.WorkloadApi
import io.github.vlsergey.recommend4me.api.model.QueuedWork
import io.github.vlsergey.recommend4me.api.model.WorkReason
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset

/** THE QUEUE OF THE BACKGROUND WORKS, for the page of the models: what runs, what waits, what is planned, and why. */
@RestController
class WorkloadController(private val workload: Workload, private val stores: Stores) : WorkloadApi {

    override fun listWorkload(): ResponseEntity<List<QueuedWork>> {
        val running = listOfNotNull(workload.running()).map { it.toApi(QueuedWork.State.RUNNING) }
        val besides = workload.besides().map { it.toApi(QueuedWork.State.BESIDE) }
        val waiting = workload.waiting().map { it.toApi(QueuedWork.State.WAITING) }
        val planned = workload.planned().map { p -> queued(p.work, QueuedWork.State.PLANNED, p.since, p.reasons, p.due) }
        return ResponseEntity.ok(running + besides + waiting + planned)
    }

    private fun HeavyWork.toApi(state: QueuedWork.State) = queued(work, state, since, reasons, null)

    private fun queued(work: Work, state: QueuedWork.State, since: Instant, reasons: Map<String, Int>, due: Instant?): QueuedWork {
        val source = work.source?.let(stores::source)
        val def = work.facet?.let { f -> source?.schema?.facets?.firstOrNull { it.key == f } }
        return QueuedWork(
            what = work.what, state = state,
            type = work.type ?: source?.type, source = work.source, facet = work.facet,
            labelKey = def?.let { I18nKeys.facetLabel(source!!.id, it) }, item = work.item,
            since = since.atOffset(ZoneOffset.UTC), due = due?.atOffset(ZoneOffset.UTC),
            reasons = reasons.map { (key, count) -> WorkReason(key, count) },
        )
    }
}
