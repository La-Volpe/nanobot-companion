package de.arjmandi.nanobot.companion.states

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The states (the Android twin of iOS's `StatesViewModelTests`): the connection dot and banner, the
 * notices as rows, the goal as a strip and never a row, the subtask dock, a delivery that failed,
 * and the one snackbar above the composer with a precedence two sources cannot argue about.
 *
 * One owner per state is the rule this file exists for: the banner belongs to the connection and
 * nothing else, the goal is a strip, a notice is a row, a settled subtask is not in the dock.
 */
class StatesViewModelTest {

    private fun model(now: Long = 0) = StatesViewModel(now = { now })

    private fun recovery(id: String = "r1", canContinue: Boolean = true) =
        ThreadNotice.Recovery(id = id, status = "awaiting_user", canContinue = canContinue, reason = "A task was interrupted while writing.")

    private fun retry(id: String = "x1", retryAtMs: Long? = 3_000) =
        ThreadNotice.Retry(id = id, attempt = 2, maxAttempts = 3, errorKind = "timeout", retryAtMs = retryAtMs)

    private fun compaction(id: String = "c1", phase: String = "succeeded") =
        ThreadNotice.Compaction(id = id, phase = phase, reason = null)

    /** One snackbar, and the precedence decides which. */
    @Test
    fun snackbarPrecedence() {
        val model = model(now = 0)

        // Nothing transient: no snackbar at all.
        model.update(notices = listOf(compaction()), subagents = emptyList(), nowMs = 0)
        assertNull(model.snackbar, "a settled compaction is a row, not a snackbar")

        // A retry with a clock is transient, so it takes the snackbar.
        model.update(notices = listOf(retry()), subagents = emptyList(), nowMs = 0)
        assertEquals(SnackbarKind.Retry, model.snackbar?.kind)
        assertEquals(3, model.snackbar?.secondsRemaining, "rounded up, and never 0s while the gateway waits")

        // A delivery the gateway refused outranks the retry: it is the one the user can act on.
        model.deliveryFailed("One more: does the queue survive a reload?")
        assertEquals(SnackbarKind.Delivery, model.snackbar?.kind, "the message that did not go out outranks everything")

        // The resend settles it, and the retry comes back rather than being lost.
        model.deliverySucceeded()
        assertEquals(SnackbarKind.Retry, model.snackbar?.kind, "the transient one underneath was never thrown away")

        // A compaction that has started is the quietest of the three.
        model.update(notices = listOf(compaction(phase = "started")), subagents = emptyList(), nowMs = 0)
        assertEquals(SnackbarKind.Compaction, model.snackbar?.kind)
    }

    @Test
    fun eachStateHasOneOwner() {
        val model = model()
        model.update(
            connection = ConnectionView(ConnectionStatus.Offline, everConnected = true),
            notices = listOf(ThreadNotice.Goal(id = "g1", active = true, status = "active", summary = "Ship the Android app")),
            subagents = emptyList(),
            nowMs = 0,
        )

        assertEquals(ConnectionDot.Reconnecting, model.dot, "a socket that was up and dropped is reconnecting")
        assertEquals(ConnectionBannerText.Reconnecting, model.banner?.text)
        assertEquals("Ship the Android app", model.goalStrip)
        assertTrue(model.rows.isEmpty(), "the goal is a strip, never a transcript row")
    }

    @Test
    fun offlineIsOnlyDisconnectedWhenItNeverConnected() {
        val model = model()
        model.update(connection = ConnectionView(ConnectionStatus.Offline, everConnected = false), notices = emptyList(), subagents = emptyList(), nowMs = 0)

        assertEquals(ConnectionDot.Offline, model.dot)
        assertEquals(ConnectionBannerText.Disconnected, model.banner?.text)
        assertTrue(model.banner?.showsRetry == true, "\"try again\" is honest when the socket never opened")
    }

    @Test
    fun aFailedHandshakeNamesTheTaxonomyEntry() {
        val model = model()
        model.update(
            connection = ConnectionView(
                ConnectionStatus.Failed,
                everConnected = false,
                failure = de.arjmandi.nanobot.companion.facade.ConnectionFailure.AuthFailed,
            ),
            notices = emptyList(),
            subagents = emptyList(),
            nowMs = 0,
        )

        assertEquals(ConnectionDot.Failed, model.dot)
        assertEquals(ConnectionBannerText.AuthFailed, model.banner?.text, "the banner is the taxonomy sentence, not one generic line")
    }

    @Test
    fun recoveryAsksForADecisionBeforeAnythingElse() {
        val model = model()
        model.update(notices = listOf(retry(), recovery(), compaction()), subagents = emptyList(), nowMs = 0)

        assertEquals(StateRowKind.Recovery, model.rows.first().kind, "a decision the user owes comes first")
        assertEquals(listOf("Continue", "Dismiss"), model.rows.first().actions.map { it.title })
        assertEquals(
            listOf(StateRowKind.Recovery, StateRowKind.Compaction),
            model.rows.map { it.kind },
            "and the quiet one follows; the retry is the snackbar's, not a row",
        )
    }

    @Test
    fun aRecoveryTheGatewayCannotRestoreOffersOnlyDismiss() {
        val model = model()
        model.update(notices = listOf(recovery(canContinue = false)), subagents = emptyList(), nowMs = 0)

        assertEquals(listOf("Dismiss"), model.rows.single().actions.map { it.title })
    }

    @Test
    fun dismissingARowDropsItBeforeTheRoundTrip() {
        var dismissed = mutableListOf<String>()
        val model = StatesViewModel(now = { 0 }, onDismiss = { dismissed += it })
        model.update(notices = listOf(ThreadNotice.Sandbox(id = "s1", issue = SandboxIssue.LauncherMissing)), subagents = emptyList(), nowMs = 0)

        model.dismiss("s1")

        assertTrue(model.rows.isEmpty(), "the row goes at once; the core's own dismissal is the same answer")
        assertEquals(listOf("s1"), dismissed)
    }

    @Test
    fun theDockHoldsOnlyActiveSubtasks() {
        val model = model()
        model.update(
            notices = emptyList(),
            subagents = listOf(
                SubagentRow(id = "a", label = "audit attachment limits", state = "running", iteration = 2, elapsedSeconds = 4.5),
                SubagentRow(id = "b", label = "settled work", state = "completed", iteration = 9, elapsedSeconds = 60.0),
            ),
            nowMs = 0,
        )

        assertEquals(listOf("a"), model.dock.map { it.id }, "a settled subtask is not in the dock")
        assertEquals("Running · step 2", model.dock.single().caption, "the gateway does not say how many steps there will be")
    }

    @Test
    fun theDebugMenuReachesAllEightFailuresAndTheChatStates() {
        assertEquals(8, DebugState.failureScenarios.size, "the eight taxonomy entries, by hand")
        assertEquals(8, DebugState.ChatState.entries.size)

        val model = model()
        model.debugFail(de.arjmandi.nanobot.companion.facade.ConnectionFailure.MissingCapability, detail = "webui.subagents.v1")

        assertEquals(ConnectionDot.Failed, model.dot)
        assertEquals(ConnectionBannerText.MissingCapability, model.banner?.text)

        model.debugClear()
        assertEquals(ConnectionDot.Open, model.dot)

        model.debugInject(DebugState.ChatState.NotSent)
        assertEquals(SnackbarKind.Delivery, model.snackbar?.kind)
    }

    @Test
    fun aFailedCompactionIsAnErrorRow() {
        val model = model()
        model.update(notices = listOf(compaction(phase = "failed")), subagents = emptyList(), nowMs = 0)

        assertTrue(model.rows.single().isError)
        assertEquals(StateRowKind.Compaction, model.rows.single().kind)
    }
}
