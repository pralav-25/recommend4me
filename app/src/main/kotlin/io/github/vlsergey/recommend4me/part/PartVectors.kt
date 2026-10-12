package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import jakarta.annotation.PreDestroy
import org.springframework.context.ApplicationEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(PartVectors::class.java)

/**
 * The text of every part of every work read, encoded window by window in the background: a vector
 * for every few hundred words, so a chapter is a set of vectors and one scene unlike the rest is
 * not averaged away. A part read again with another text is encoded again.
 */
@Service
class PartVectors(private val stores: Stores, private val plugins: Plugins, private val events: ApplicationEventPublisher) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "part-vectors").apply { isDaemon = true } }
    private val wake = Object()

    @Volatile
    private var stopping = false

    @Volatile
    private var busy = false

    fun running(): Boolean = busy

    @EventListener
    fun partsSaved(event: PartsSaved) = synchronized(wake) { wake.notifyAll() }

    @Order(5)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.execute {
            while (!stopping) {
                val did = try {
                    encodeSome()
                } catch (e: Exception) {
                    log.error("parts were not encoded", e)
                    0
                }
                if (did == 0) {
                    busy = false
                    try {
                        synchronized(wake) { wake.wait(TimeUnit.MINUTES.toMillis(10)) }
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                }
            }
        }
    }

    /** Encodes a batch of parts of every source; returns how many. */
    private fun encodeSome(): Int {
        val encoder = plugins.textEncoder() ?: return 0
        var done = 0
        stores.sources.forEach { s ->
            val todo = s.parts.toEncode(encoder.id, BATCH)
            if (todo.isEmpty()) return@forEach
            busy = true
            val windows = encoder.encodeWindows(todo.map { it.content })
            todo.forEachIndexed { i, part -> s.parts.saveVectors(part.itemId, part.partId, encoder.id, part.hash, windows[i]) }
            // The works searched by the meaning of their chapters' windows: indexed again with them
            events.publishEvent(TextVectorsChanged(s.id, todo.map { it.itemId }.distinct()))
            done += todo.size
        }
        return done
    }

    fun pending(): Int = plugins.textEncoder()?.let { e -> stores.sources.sumOf { it.parts.countToEncode(e.id) } } ?: 0

    fun total(): Int = stores.sources.sumOf { it.parts.countWithText() }

    @PreDestroy
    fun shutdown() {
        stopping = true
        worker.shutdownNow()
    }

    companion object {
        /** Parts per encoder call: a chapter is tens of windows already. */
        private const val BATCH = 8
    }
}
