package de.arjmandi.nanobot.companion.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * When a frame is worth a banner. The bar is the WebUI's own (`lib/browser-notifications.ts`:
 * `turn_end` unless cancelled, plus a recovery that is `awaiting_user` or `failed`), with one
 * addition the browser does not need: a chat the user is looking at is never announced, because
 * the answer is already on screen in front of them.
 */
class TaskNoticeRuleTest {

    private val chat = "websocket:a"
    private val away = ScreenPresence(isForeground = false, visibleChatId = null)

    private fun decide(
        event: TaskEvent?,
        presence: ScreenPresence = away,
        seen: List<String> = emptyList(),
    ): NoticeDecision? = event?.let { TaskNoticeRule.decide(it, presence, seen) }

    @Test
    fun aTurnThatFinishesWhileTheAppIsAwayDeservesABanner() {
        val event = TaskNoticeRule.turnEnd(chat, "turn-1", outcome = null)

        val decision = decide(event)

        val deliver = assertIs<NoticeDecision.Deliver>(decision, "a settled turn away from the app: $decision")
        assertEquals(chat, deliver.notice.chatId)
        assertEquals(TaskOutcome.Completed, deliver.notice.outcome)
    }

    @Test
    fun theChatOnScreenIsNeverAnnounced() {
        val event = TaskNoticeRule.turnEnd(chat, "turn-1", outcome = null)

        val decision = decide(event, ScreenPresence(isForeground = true, visibleChatId = chat))

        assertEquals(NoticeDecision.Silent(NoticeSilence.ChatOnScreen), decision)
    }

    @Test
    fun aBackgroundedAppStillAnnouncesTheChatItLeftOnScreen() {
        // Backgrounded is not "looking at it": the thread is behind the home screen, so the
        // answer nobody can see is exactly what a banner is for.
        val event = TaskNoticeRule.turnEnd(chat, "turn-1", outcome = null)

        val decision = decide(event, ScreenPresence(isForeground = false, visibleChatId = chat))

        assertIs<NoticeDecision.Deliver>(decision)
    }

    @Test
    fun aTurnOnAnotherChatIsAnnouncedWhileTheAppIsForeground() {
        // The drawer, the automations page, a different thread: the settled chat is off screen.
        val event = TaskNoticeRule.turnEnd(chat, "turn-1", outcome = null)

        val decision = decide(event, ScreenPresence(isForeground = true, visibleChatId = "websocket:b"))

        assertIs<NoticeDecision.Deliver>(decision)
    }

    @Test
    fun aCancelledTurnIsNotWorthAWakeup() {
        // The user stopped it, so the browser's rule stays quiet and so does this one.
        assertNull(TaskNoticeRule.turnEnd(chat, "turn-1", outcome = "cancelled"))
    }

    @Test
    fun aFailedTurnSaysFailed() {
        val event = TaskNoticeRule.turnEnd(chat, "turn-1", outcome = "failed", failureKind = "provider_error")

        val deliver = assertIs<NoticeDecision.Deliver>(decide(event))

        assertEquals(TaskOutcome.Failed, deliver.notice.outcome)
    }

    @Test
    fun aTurnWithAFailureKindAndNoOutcomeIsAFailure() {
        // `turn_end.outcome` is omitted when the turn completed, so a failure is either word
        // (`outbound_wire.py:344`); ThreadReducer reads both, and so must this.
        val event = TaskNoticeRule.turnEnd(chat, "turn-1", outcome = null, failureKind = "tool_error")

        assertEquals(TaskOutcome.Failed, assertIs<NoticeDecision.Deliver>(decide(event)).notice.outcome)
    }

    @Test
    fun anInterruptedTurnSaysInterrupted() {
        val event = TaskNoticeRule.turnEnd(chat, "turn-1", outcome = "interrupted")

        assertEquals(TaskOutcome.Interrupted, assertIs<NoticeDecision.Deliver>(decide(event)).notice.outcome)
    }

    @Test
    fun aRecoveryWaitingForTheUserDeservesABanner() {
        val event = TaskNoticeRule.recoveryState(chat, "rec-1", status = "awaiting_user")

        val deliver = assertIs<NoticeDecision.Deliver>(decide(event))

        assertEquals(TaskOutcome.NeedsAttention, deliver.notice.outcome)
        assertEquals(chat, deliver.notice.chatId)
    }

    @Test
    fun aRecoveryThatIsAlreadyResumingIsSilent() {
        // `resuming` is the gateway working on it; only the two states that need a person do.
        assertNull(TaskNoticeRule.recoveryState(chat, "rec-1", status = "resuming"))
        assertNull(TaskNoticeRule.recoveryState(chat, "rec-1", status = "recovered"))
    }

    @Test
    fun aFrameWithNoChatCannotBeOpenedFromABanner() {
        assertNull(TaskNoticeRule.turnEnd(null, "turn-1", outcome = null))
        assertNull(TaskNoticeRule.recoveryState(null, "rec-1", status = "awaiting_user"))
    }

    @Test
    fun theSameEventIsAnnouncedOnce() {
        val event = TaskNoticeRule.turnEnd(chat, "turn-1", outcome = null)
        val first = assertIs<NoticeDecision.Deliver>(decide(event))

        val again = decide(event, seen = listOf(first.notice.key))

        assertEquals(NoticeDecision.Silent(NoticeSilence.AlreadyShown), again)
    }

    @Test
    fun twoTurnsOfOneChatAreTwoEvents() {
        val first = assertIs<NoticeDecision.Deliver>(
            decide(TaskNoticeRule.turnEnd(chat, "turn-1", outcome = null)),
        )
        val second = assertIs<NoticeDecision.Deliver>(
            decide(TaskNoticeRule.turnEnd(chat, "turn-2", outcome = null), seen = listOf(first.notice.key)),
        )

        assertEquals(first.notice.chatId, second.notice.chatId)
        assertEquals(false, first.notice.key == second.notice.key)
    }
}
