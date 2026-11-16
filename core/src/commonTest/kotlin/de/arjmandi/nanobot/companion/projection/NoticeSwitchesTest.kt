package de.arjmandi.nanobot.companion.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T43: the Notifications screen's switches were local state that persisted nowhere and gated
 * nothing. This is the rule both posters now ask — which switch covers which notice, what it is
 * called, and what a fresh install does before the reader touches anything.
 */
class NoticeSwitchesTest {

    @Test
    fun everyNoticeFallsUnderOneSwitch() {
        assertEquals(NoticeCategory.Finished, NoticeCategory.of(TaskOutcome.Completed))
        assertEquals(NoticeCategory.FailedOrStopped, NoticeCategory.of(TaskOutcome.Failed))
        assertEquals(
            NoticeCategory.FailedOrStopped,
            NoticeCategory.of(TaskOutcome.Interrupted),
            "a stopped turn is one the reader watched not finish: the row says \"or stopped\" for this",
        )
        assertEquals(NoticeCategory.NeedsAttention, NoticeCategory.of(TaskOutcome.NeedsAttention))
    }

    @Test
    fun aSwitchThatIsOffSilencesTheOutcomesItNames() {
        val all = NoticeSwitches(finished = true, failedOrStopped = true, needsAttention = true)
        assertTrue(TaskOutcome.entries.all { all.allows(it) }, "three switches on is every notice")

        val quiet = NoticeSwitches(finished = false, failedOrStopped = false, needsAttention = false)
        assertFalse(TaskOutcome.entries.any { quiet.allows(it) }, "and three off is silence")

        assertTrue(NoticeSwitches(finished = true).allows(TaskOutcome.Completed))
        assertFalse(NoticeSwitches(finished = false).allows(TaskOutcome.Completed))
        assertFalse(NoticeSwitches(failedOrStopped = false).allows(TaskOutcome.Failed))
        assertFalse(NoticeSwitches(failedOrStopped = false).allows(TaskOutcome.Interrupted))
    }

    @Test
    fun aFreshInstallPostsWhatItsSwitchesSay() {
        val fresh = NoticeSwitches()

        assertTrue(fresh.allows(TaskOutcome.Completed), "the design draws finished on")
        assertTrue(fresh.allows(TaskOutcome.Failed), "and failed on")
        assertTrue(fresh.allows(TaskOutcome.Interrupted), "and stopped with it")
        assertFalse(fresh.allows(TaskOutcome.NeedsAttention), "needs-attention is drawn off")
    }

    /**
     * Both phones write and read these keys from their own store, so the strings are part of the
     * preference a reader already has: changing one silently forgets a choice.
     */
    @Test
    fun theKeysAreTheOnesBothPhonesStore() {
        assertEquals(
            listOf("settings.notify.finished", "settings.notify.failedOrStopped", "settings.notify.needsAttention"),
            NoticeCategory.entries.map { it.preferenceKey },
        )
        assertEquals(NoticeCategory.entries.size, NoticeCategory.entries.map { it.preferenceKey }.toSet().size)
    }
}
