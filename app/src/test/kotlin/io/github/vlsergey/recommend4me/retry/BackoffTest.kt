package io.github.vlsergey.recommend4me.retry

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackoffTest {

    @Test
    fun `the wait doubles from a second after every failure`() {
        val backoff = Backoff()
        assertEquals(Duration.ZERO, backoff.delayAfter(0))
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L), (1..5).map { backoff.delayAfter(it).seconds })
        // A day and a half after the eighteenth failure
        assertEquals(Duration.ofSeconds(1L shl 17), backoff.delayAfter(18))
    }

    @Test
    fun `the wait is counted by the settings and kept under the bound if there is one`() {
        val backoff = Backoff(first = Duration.ofMillis(500), factor = 3.0, max = Duration.ofMinutes(1))
        assertEquals(listOf(500L, 1500L, 4500L, 13500L, 40500L, 60000L, 60000L), (1..7).map { backoff.delayAfter(it).toMillis() })
        assertFailsWith<IllegalArgumentException> { Backoff(first = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { Backoff(factor = 0.5) }
        assertFailsWith<IllegalArgumentException> { Backoff(first = Duration.ofMinutes(2), max = Duration.ofMinutes(1)) }
    }

    @Test
    fun `without a bound the wait grows without end, the next attempt never past the latest moment kept`() {
        val backoff = Backoff()
        val now = Instant.parse("2026-10-21T12:00:00Z")
        assertEquals(now.plusSeconds(4), backoff.nextAttempt(now, 3))
        val attempts = (1..2000).map { backoff.nextAttempt(now, it) }
        // Later every time till the latest moment a database keeps, and there it stays
        assertTrue(attempts.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(Backoff.LATEST, attempts.last())
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE), backoff.delayAfter(2000))
    }
}
