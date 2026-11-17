package de.arjmandi.nanobot.companion.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconnect pacing. Deterministic here because the jitter source is
 * injected: a test that needs the wall clock cannot prove ten attempts in milliseconds,
 * and an app that retries every 100 ms drains a phone battery on a dead gateway.
 */
class BackoffTest {

    private fun backoff(random: Double = 0.5) = Backoff(BackoffPolicy(), random = { random })

    @Test
    fun growsExponentiallyWithJitterCappedAt30s() {
        val policy = BackoffPolicy(baseMs = 500, maxMs = 30_000, jitterFraction = 0.2)

        // random = 0.5 is the middle of the jitter band, so the delays are exact.
        val middle = (0..10).map { attempt -> policy.delayMs(attempt, random = 0.5) }

        assertEquals(500L, middle[0], "the first retry is quick")
        assertEquals(1_000L, middle[1])
        assertEquals(2_000L, middle[2])
        assertEquals(4_000L, middle[3])
        assertTrue(middle.all { it <= 30_000 }, "the cap holds: $middle")
        assertEquals(30_000L, middle[10], "a long outage retries every 30 s, not every hour")

        assertTrue(policy.delayMs(3, random = 0.0) < policy.delayMs(3, random = 1.0), "jitter spreads the herd")
        assertTrue(policy.delayMs(3, random = 0.0) >= 3_200, "jitter stays inside the band")
        assertTrue(policy.delayMs(3, random = 1.0) <= 4_800)
    }

    @Test
    fun resetsAfterStableOpen() {
        val attempt = backoff(random = 0.5)

        repeat(4) { attempt.nextDelayMs() } // four failed tries
        assertEquals(8_000L, attempt.nextDelayMs(), "the fifth try is far apart")

        attempt.onConnectionClosed(openForMs = 30_000)
        assertEquals(500L, attempt.nextDelayMs(), "a connection that stayed up long enough starts the ladder again")

        attempt.onConnectionClosed(openForMs = 200)
        assertTrue(attempt.nextDelayMs() > 500, "a connection that flapped keeps the backoff")
    }
}
