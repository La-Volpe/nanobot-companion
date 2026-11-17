package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reducers and notice expiry read time through this seam and never through the wall
 * clock ("time passed in"), so the fake has to advance predictably.
 */
class FakeClockTest {

    @Test
    fun advanceMovesNow() {
        val clock = FakeClock(startMs = 1_000)

        assertEquals(1_000, clock.nowMs())

        clock.advanceBy(250)
        assertEquals(1_250, clock.nowMs())

        clock.advanceBy(0)
        assertEquals(1_250, clock.nowMs())
    }

    @Test
    fun settingTimeDirectlyIsAllowedForScenarios() {
        val clock = FakeClock()

        clock.setTo(1_794_623_001_668)

        assertEquals(1_794_623_001_668, clock.nowMs())
    }
}
