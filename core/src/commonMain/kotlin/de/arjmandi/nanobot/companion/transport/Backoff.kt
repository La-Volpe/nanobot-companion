package de.arjmandi.nanobot.companion.transport

/**
 * Reconnect pacing. Pure: the jitter source is injected, so ten attempts can be proven in a
 * test without waiting, and the policy is the same on both platforms.
 */
internal data class BackoffPolicy(
    val baseMs: Long = 500,
    val maxMs: Long = 30_000,
    val jitterFraction: Double = 0.2,
    /** A connection that stayed up this long was healthy, so the ladder starts over. */
    val stableAfterMs: Long = 10_000,
) {
    /** [random] is in `[0, 1)`; the result stays inside `base * 2^attempt` ± the band. */
    fun delayMs(attempt: Int, random: Double): Long {
        val exponential = baseMs.toDouble() * pow2(attempt)
        val spread = exponential * jitterFraction
        val jittered = exponential - spread + 2 * spread * random
        return jittered.toLong().coerceIn(baseMs, maxMs)
    }

    private fun pow2(attempt: Int): Double {
        var result = 1.0
        repeat(attempt.coerceIn(0, 30)) { result *= 2.0 }
        return result
    }
}

/** The ladder as state, because "which attempt am I on" is what actually gets reset. */
internal class Backoff(
    private val policy: BackoffPolicy = BackoffPolicy(),
    private val random: () -> Double = { 0.5 },
) {

    private var attempt: Int = 0

    fun nextDelayMs(): Long = policy.delayMs(attempt.coerceAtMost(20), random()).also { attempt++ }

    fun onConnectionClosed(openForMs: Long) {
        if (openForMs >= policy.stableAfterMs) attempt = 0
    }
}
