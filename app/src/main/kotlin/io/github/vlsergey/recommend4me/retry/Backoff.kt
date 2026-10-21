package io.github.vlsergey.recommend4me.retry

import java.time.Duration
import java.time.Instant

/**
 * When a thing that failed is tried again: [first] after the first failure, [factor] times longer
 * after every next one, never longer than [max] — without a bound when it is null, the wait growing
 * without end. The attempts are counted by the one who tries: the number of failures so far.
 */
data class Backoff(
    val first: Duration = Duration.ofSeconds(1),
    val factor: Double = 2.0,
    val max: Duration? = null,
) {
    init {
        require(!first.isNegative && !first.isZero) { "first $first" }
        require(factor >= 1.0) { "factor $factor" }
        require(max == null || max >= first) { "max $max below first $first" }
    }

    /** How long to wait after the [failures]-th failure in a row: none before the first. */
    fun delayAfter(failures: Int): Duration {
        if (failures <= 0) return Duration.ZERO
        val seconds = seconds(first) * Math.pow(factor, (failures - 1).toDouble())
        val bounded = max?.let { minOf(seconds, seconds(it)) } ?: seconds
        // Past what a duration holds, some 292 billion years: as long as one can
        if (bounded >= Long.MAX_VALUE.toDouble()) return Duration.ofSeconds(Long.MAX_VALUE)
        val whole = Math.floor(bounded).toLong()
        return Duration.ofSeconds(whole, ((bounded - whole) * 1e9).toLong())
    }

    /** When to try again what failed the [failures]-th time in a row at [failedAt]: never past [LATEST]. */
    fun nextAttempt(failedAt: Instant, failures: Int): Instant {
        val delay = delayAfter(failures)
        return if (Duration.between(failedAt, LATEST) <= delay) LATEST else failedAt + delay
    }

    companion object {
        /** The latest moment a database keeps: a wait growing without bound ends here, in the year 9999. */
        val LATEST: Instant = Instant.parse("9999-12-31T23:59:59Z")

        private fun seconds(d: Duration) = d.seconds + d.nano / 1e9
    }
}
