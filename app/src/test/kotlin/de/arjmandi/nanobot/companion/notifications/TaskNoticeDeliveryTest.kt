package de.arjmandi.nanobot.companion.notifications

import de.arjmandi.nanobot.companion.projection.NoticeSwitches
import de.arjmandi.nanobot.companion.projection.NotificationPermission
import de.arjmandi.nanobot.companion.projection.TaskNotice
import de.arjmandi.nanobot.companion.projection.TaskOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone half of a task notice: post what the core allowed, remember what the system refused,
 * and ask for the permission once — at the first banner that went missing, not at launch.
 */
class TaskNoticeDeliveryTest {

    private class FakePosting(
        var permission: NotificationPermission = NotificationPermission.Granted,
        var accepts: Boolean = true,
    ) : BannerPosting {
        val posted: MutableList<String> = mutableListOf()

        override fun permission(): NotificationPermission = permission

        override fun post(notice: TaskNotice): Boolean {
            if (!accepts) return false
            posted += notice.key
            return true
        }
    }

    /** Every switch on: the tests that are not about the switches are about the posting. */
    private val allOn: () -> NoticeSwitches = { NoticeSwitches(finished = true, failedOrStopped = true, needsAttention = true) }

    private fun notice(
        key: String = "websocket:a|turn:turn-1|Completed",
        outcome: TaskOutcome = TaskOutcome.Completed,
    ) = TaskNotice(
        chatId = "websocket:a",
        outcome = outcome,
        key = key,
    )

    @Test
    fun aNoticeTheCoreAllowedIsPostedOnce() {
        val posting = FakePosting()
        val delivery = TaskNoticeDelivery(posting, switches = allOn)

        assertTrue(delivery.deliver(notice()))
        assertFalse(delivery.deliver(notice()), "the same event is not a second banner")

        assertEquals(1, posting.posted.size)
    }

    @Test
    fun aBannerTheSystemRefusedIsRemembered() {
        val posting = FakePosting(permission = NotificationPermission.NotAsked, accepts = false)
        val delivery = TaskNoticeDelivery(posting, switches = allOn)

        assertFalse(delivery.deliver(notice()))

        assertTrue(delivery.missedNotice)
        assertTrue(delivery.shouldAskPermission, "a missed banner is the moment to ask")
    }

    @Test
    fun thePermissionIsNotAskedBeforeAnythingWentMissing() {
        val posting = FakePosting(permission = NotificationPermission.NotAsked)
        val delivery = TaskNoticeDelivery(posting, switches = allOn)

        assertFalse(delivery.shouldAskPermission)
    }

    @Test
    fun aRefusalIsNotAskedForAgain() {
        // Android will not draw the dialog twice; the honest offer is the system's own screen.
        val posting = FakePosting(permission = NotificationPermission.Denied, accepts = false)
        val delivery = TaskNoticeDelivery(posting, switches = allOn)
        delivery.deliver(notice())

        assertFalse(delivery.shouldAskPermission)
    }

    /**
     * T43: the Notifications switches have to gate something. A reader who turned "Needs attention"
     * off is not posted a recovery, and the suppressed notice is not remembered either — turning the
     * switch back on lets the next one through, which is what a switch means.
     */
    @Test
    fun aSwitchThatIsOffSilencesTheNoticeItNames() {
        val posting = FakePosting()
        var switches = NoticeSwitches(needsAttention = false)
        val delivery = TaskNoticeDelivery(posting, switches = { switches })
        val attention = TaskNotice(
            chatId = "websocket:a",
            outcome = TaskOutcome.NeedsAttention,
            key = "websocket:a|recovery:r-1|NeedsAttention",
        )

        assertFalse(delivery.deliver(attention), "the switch the reader turned off says no")
        assertTrue(posting.posted.isEmpty())
        assertFalse(delivery.missedNotice, "a switch is not a missing permission: nothing to ask for")

        switches = NoticeSwitches(needsAttention = true)
        assertTrue(delivery.deliver(attention), "the switch was never a memory of this notice")
        assertEquals(1, posting.posted.size)
    }

    /** The name of a switch is the set of outcomes it gates: stopping a turn is not finishing it. */
    @Test
    fun theFailedSwitchCoversAFailureAndAStop() {
        val posting = FakePosting()
        val delivery = TaskNoticeDelivery(posting, switches = { NoticeSwitches(failedOrStopped = false) })

        assertFalse(delivery.deliver(notice(outcome = TaskOutcome.Failed, key = "websocket:a|turn:t-2|Failed")))
        assertFalse(delivery.deliver(notice(outcome = TaskOutcome.Interrupted, key = "websocket:a|turn:t-3|Interrupted")))
        assertTrue(posting.posted.isEmpty(), "both are the row the reader turned off")
    }

    @Test
    fun aGrantedAnswerLetsTheNextNoticeThrough() {
        val posting = FakePosting(permission = NotificationPermission.NotAsked, accepts = false)
        val delivery = TaskNoticeDelivery(posting, switches = allOn)
        delivery.deliver(notice())

        posting.permission = NotificationPermission.Granted
        posting.accepts = true
        delivery.permissionAnswered(granted = true)

        assertFalse(delivery.missedNotice)
        assertTrue(delivery.deliver(notice()))
    }
}
