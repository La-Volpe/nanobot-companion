package de.arjmandi.nanobot.companion.notifications

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

    private fun notice(key: String = "websocket:a|turn:turn-1|Completed") = TaskNotice(
        chatId = "websocket:a",
        outcome = TaskOutcome.Completed,
        key = key,
    )

    @Test
    fun aNoticeTheCoreAllowedIsPostedOnce() {
        val posting = FakePosting()
        val delivery = TaskNoticeDelivery(posting)

        assertTrue(delivery.deliver(notice()))
        assertFalse(delivery.deliver(notice()), "the same event is not a second banner")

        assertEquals(1, posting.posted.size)
    }

    @Test
    fun aBannerTheSystemRefusedIsRemembered() {
        val posting = FakePosting(permission = NotificationPermission.NotAsked, accepts = false)
        val delivery = TaskNoticeDelivery(posting)

        assertFalse(delivery.deliver(notice()))

        assertTrue(delivery.missedNotice)
        assertTrue(delivery.shouldAskPermission, "a missed banner is the moment to ask")
    }

    @Test
    fun thePermissionIsNotAskedBeforeAnythingWentMissing() {
        val posting = FakePosting(permission = NotificationPermission.NotAsked)
        val delivery = TaskNoticeDelivery(posting)

        assertFalse(delivery.shouldAskPermission)
    }

    @Test
    fun aRefusalIsNotAskedForAgain() {
        // Android will not draw the dialog twice; the honest offer is the system's own screen.
        val posting = FakePosting(permission = NotificationPermission.Denied, accepts = false)
        val delivery = TaskNoticeDelivery(posting)
        delivery.deliver(notice())

        assertFalse(delivery.shouldAskPermission)
    }

    @Test
    fun aGrantedAnswerLetsTheNextNoticeThrough() {
        val posting = FakePosting(permission = NotificationPermission.NotAsked, accepts = false)
        val delivery = TaskNoticeDelivery(posting)
        delivery.deliver(notice())

        posting.permission = NotificationPermission.Granted
        posting.accepts = true
        delivery.permissionAnswered(granted = true)

        assertFalse(delivery.missedNotice)
        assertTrue(delivery.deliver(notice()))
    }
}
