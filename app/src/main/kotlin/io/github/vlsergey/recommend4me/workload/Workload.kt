package io.github.vlsergey.recommend4me.workload

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

private val log = LoggerFactory.getLogger(Workload::class.java)

/** A heavy work: what it is, since when it waits or runs. */
class HeavyWork(val name: String, val since: Instant)

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
 */
@Component
class Workload {
    /** Fair: the longest waiting goes first. */
    private val lock = ReentrantLock(true)
    private val waiting = ConcurrentLinkedQueue<HeavyWork>()

    @Volatile
    private var current: HeavyWork? = null

    /** Runs [work] once every heavy work asked for before it is done; answers what it answers. */
    fun <T> run(name: String, work: () -> T): T {
        if (lock.isHeldByCurrentThread) return work()
        val entry = HeavyWork(name, Instant.now())
        waiting += entry
        try {
            lock.lockInterruptibly()
        } finally {
            waiting -= entry
        }
        try {
            val waited = Duration.between(entry.since, Instant.now())
            if (waited > NOTED) log.info("{} waited {} s for the heavy works before it", name, waited.toSeconds())
            current = HeavyWork(name, Instant.now())
            return work()
        } finally {
            current = null
            lock.unlock()
        }
    }

    /** The heavy work running now, none when idle. */
    fun running(): HeavyWork? = current

    /** The heavy works waiting their turn, the longest waiting first. */
    fun waiting(): List<HeavyWork> = waiting.toList().sortedBy { it.since }

    private companion object {
        /** A wait worth telling in the log. */
        val NOTED: Duration = Duration.ofSeconds(2)
    }
}
