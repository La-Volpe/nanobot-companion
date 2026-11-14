package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The production clock is the platform's millisecond wall clock. If this drifts, every
 * timestamp the app shows drifts with it, and the fixture replay tests stop matching.
 */
class SystemClockTest {

    @Test
    fun matchesJvmMillis() {
        val before = System.currentTimeMillis()
        val ours = systemClock().nowMs()
        val after = System.currentTimeMillis()

        assertTrue(ours >= before, "systemClock().nowMs()=$ours predates the JVM clock ($before)")
        assertTrue(ours <= after, "systemClock().nowMs()=$ours is ahead of the JVM clock ($after)")
    }

    @Test
    fun advancesWithTheMachine() {
        val clock = systemClock()

        val first = clock.nowMs()
        Thread.sleep(5)
        val second = clock.nowMs()

        assertTrue(second >= first, "a wall clock does not go backwards")
    }
}
