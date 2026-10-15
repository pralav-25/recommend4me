package io.github.vlsergey.recommend4me.workload

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

private val log = LoggerFactory.getLogger(Workload::class.java)

/**
 * What a work does — [what], a key the interface words as "workload.<what>" — and of what: a content
 * type, a source, a facet, a work. Two works alike are the same work: planned twice, it is planned once.
 */
data class Work(val what: String, val type: String? = null, val source: String? = null, val facet: String? = null, val item: String? = null) {
    override fun toString() = listOfNotNull(type, source, facet, item).joinToString("/").ifEmpty { "all" } + ": " + what
}

/**
 * A work waiting or running: since when, and why — the changes that asked for it, by the key the
 * interface words as "workload.reason.<key>", each with how many times it came.
 */
class HeavyWork(val work: Work, val since: Instant, val reasons: Map<String, Int>)

/** A work planned for later: when it is due (it may be put off by newer changes), since when it is planned, and why. */
class PlannedWork(val work: Work, val due: Instant, val since: Instant, val reasons: Map<String, Int>)

/**
 * THE HEAVY WORKS, ONE AT A TIME, IN THE ORDER ASKED: every computation that holds a whole catalogue
 * or fits a model — the training of the grades and of interest, a model that had not seen a graded
 * work, the suggesters of the facets, the comparisons of methods, the counting of the tags, the
 * directions of the sets, the index of the search. Each read 27 718 games with their vectors, a
 * gigabyte and more; eight at once — a training in the background, the held-out models of the pages
 * opened meanwhile — ran the heap out (2026-10-15). Whoever asks — a background thread, a request —
 * waits its turn here: the memory holds one of them, whatever their number.
 *
 * Reentrant: a heavy work asking for another inside it goes on. Taken before any lock of its own —
 * a lock held while waiting here would hold up whoever runs.
 *
 * AND WHAT IS PLANNED, AND WHY: the background works scheduled after the changes that ask for them
 * ([plan]) — a grade, an answer, a tag — are told here with their reasons, which go with them once
 * they begin ([take]). The works of the background that do not hold a catalogue — the reranker
 * reading pairs, the vectors of the tags — run [alongside] the queue and are told too. Of the works
 * nothing is kept but what they are, their times and their reasons, and nothing outlives the process.
 */
@Component
class Workload {
    /** Fair: the longest waiting goes first. */
    private val lock = ReentrantLock(true)
    private val waiting = ConcurrentLinkedQueue<HeavyWork>()
    private val planned = ConcurrentHashMap<Work, PlannedWork>()
    private val besides = ConcurrentLinkedQueue<HeavyWork>()

    @Volatile
    private var current: HeavyWork? = null

    /**
     * Runs [body] once every heavy work asked for before it is done; answers what it answers. The
     * reasons of the work planned alike, if any, go with it.
     */
    fun <T> run(work: Work, reasons: Map<String, Int> = take(work), body: () -> T): T {
        if (lock.isHeldByCurrentThread) return body()
        val entry = HeavyWork(work, Instant.now(), reasons)
        waiting += entry
        try {
            lock.lockInterruptibly()
        } finally {
            waiting -= entry
        }
        try {
            val waited = Duration.between(entry.since, Instant.now())
            if (waited > NOTED) log.info("{} waited {} s for the heavy works before it", work, waited.toSeconds())
            current = HeavyWork(work, Instant.now(), reasons)
            return body()
        } finally {
            current = null
            lock.unlock()
        }
    }

    /** Runs [body] now, outside the queue — it holds no catalogue — told as running beside it. */
    fun <T> alongside(work: Work, reasons: Map<String, Int> = take(work), body: () -> T): T {
        val entry = HeavyWork(work, Instant.now(), reasons)
        besides += entry
        try {
            return body()
        } finally {
            besides -= entry
        }
    }

    /**
     * The [work] planned for [due] — later than planned before, if put off; when null, as planned
     * before — for one more [reason].
     */
    fun plan(work: Work, due: Instant?, reason: String) {
        planned.compute(work) { _, old ->
            val reasons = old?.reasons.orEmpty().toMutableMap().apply { merge(reason, 1, Int::plus) }
            PlannedWork(work, due ?: old?.due ?: Instant.now(), old?.since ?: Instant.now(), reasons)
        }
    }

    /** The work begins: no longer planned; the reasons it was planned for, none if it was not. */
    fun take(work: Work): Map<String, Int> = planned.remove(work)?.reasons.orEmpty()

    /** The heavy work running now, none when idle. */
    fun running(): HeavyWork? = current

    /** The heavy works waiting their turn, the longest waiting first. */
    fun waiting(): List<HeavyWork> = waiting.toList().sortedBy { it.since }

    /** The works running outside the queue ([alongside]). */
    fun besides(): List<HeavyWork> = besides.toList().sortedBy { it.since }

    /** The works planned, the soonest due first. */
    fun planned(): List<PlannedWork> = planned.values.sortedBy { it.due }

    private companion object {
        /** A wait worth telling in the log. */
        val NOTED: Duration = Duration.ofSeconds(2)
    }
}
