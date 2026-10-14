package io.github.vlsergey.recommend4me.database

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(DatabaseCompaction::class.java)

/**
 * THE FILES COMPACTED WHILE THE APPLICATION RUNS: every [EVERY_MINUTES] minutes each file much of
 * which is unused is compacted for at most [BUDGET_MS] ([Database.compact]) — a big one over several
 * rounds, a little at a time, the writes waiting no longer than that. An idle file is compacted at
 * its closing anyway; this is for the files written all day, the model's above all.
 */
@Component
class DatabaseCompaction(private val databases: Databases) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "compaction").apply { isDaemon = true } }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        scheduler.scheduleWithFixedDelay(::compactAll, FIRST_MINUTES, EVERY_MINUTES, TimeUnit.MINUTES)
    }

    private fun compactAll() {
        databases.all().forEach { db ->
            try {
                val started = System.currentTimeMillis()
                val (before, after) = db.compact(BUDGET_MS) ?: return@forEach
                val used = db.usage()
                log.info(
                    "{}: compacted from {} MB to {} MB in {} ms; of it used {} %, of its chunks {} %",
                    db.name, before shr 20, after shr 20, System.currentTimeMillis() - started, used?.second, used?.third,
                )
            } catch (e: Exception) {
                log.warn("{}: not compacted: {}", db.name, e.message, e)
            }
        }
    }

    @PreDestroy
    fun stop() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(BUDGET_MS * 2L, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** After the start's own work has settled. */
        private const val FIRST_MINUTES = 5L
        private const val EVERY_MINUTES = 15L

        /** The longest one file is compacted at a time: its writes wait meanwhile. */
        private const val BUDGET_MS = 20_000
    }
}
