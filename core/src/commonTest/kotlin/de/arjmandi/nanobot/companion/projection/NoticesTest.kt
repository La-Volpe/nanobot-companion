package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notices that are not part of an answer: context compaction, an
 * interrupted task with a decision to make, a retry with a clock on it, the goal strip, and
 * the sandbox warning a failing `exec` produces.
 *
 * Each rule is the WebUI's, read from its source: compaction is one row per `compaction_id`
 * whose phase is replaced in place (`ContextCompactionNotice.tsx`), recovery appears only
 * while the gateway is waiting for a decision (`RecoveryNotice.tsx:41`), the retry countdown
 * is `retry_after_s` from the frame plus the clock the reducer is given
 * (`outbound_wire.py:319`), the goal strip is `ui_summary` else `objective`
 * (`ThreadComposer.tsx:515`), and the sandbox warning is an `exec` tool event with one of
 * seven `error_code`s (`useSandboxNotice.ts:5-14`).
 */
class NoticesTest {

    private val chat = "websocket:a"

    private fun state(): ThreadState = ThreadState(chatId = chat)

    private fun reduce(state: ThreadState, json: String, nowMs: Long = 1_000): ThreadState =
        ThreadReducer.reduce(state, FrameCodec.decode(json), nowMs).state

    private fun frame(event: String, body: String = ""): String =
        if (body.isEmpty()) """{"event":"$event","chat_id":"$chat"}""" else
            """{"event":"$event","chat_id":"$chat",$body}"""

    private fun notices(state: ThreadState): List<Notice> = state.notices

    @Test
    fun compactionNoticeReplacesRange() {
        val started = reduce(
            state(),
            frame("context_compaction", """"compaction_id":"cmp-1","phase":"started","reason":"context_pressure""""),
        )
        val compaction = notices(started).filterIsInstance<Notice.Compaction>().single()
        assertEquals("cmp-1", compaction.id)
        assertEquals("started", compaction.phase)
        assertEquals("context_pressure", compaction.reason)

        // The same compaction's next phase replaces the row rather than stacking a second one:
        // one compaction is one line in the thread.
        val finished = reduce(
            started,
            frame("context_compaction", """"compaction_id":"cmp-1","phase":"succeeded","reason":"context_pressure""""),
        )
        val replaced = notices(finished).filterIsInstance<Notice.Compaction>()
        assertEquals(1, replaced.size, "one compaction is one notice; got $replaced")
        assertEquals("succeeded", replaced.single().phase)

        // A different compaction is its own notice, and the finished one stays finished.
        val second = reduce(finished, frame("context_compaction", """"compaction_id":"cmp-2","phase":"started""""))
        assertEquals(listOf("cmp-1", "cmp-2"), notices(second).filterIsInstance<Notice.Compaction>().map { it.id })
    }

    @Test
    fun recoveryNoticeOffersContinueAndDismiss() {
        val waiting = reduce(
            state(),
            frame(
                "recovery_state",
                """"recovery_id":"rec-1","status":"awaiting_user","reason":"tool_state_uncertain",
                   "can_continue":true,"attempts":1""",
            ),
        )
        val recovery = notices(waiting).filterIsInstance<Notice.Recovery>().single()
        assertEquals("rec-1", recovery.id)
        assertTrue(recovery.canContinue, "the gateway says the saved task can be resumed")
        assertEquals("tool_state_uncertain", recovery.reason)

        // `can_continue: false` is the "context unavailable" case: Continue is not offered.
        val unavailable = reduce(waiting, frame("recovery_state", """"recovery_id":"rec-2","status":"failed","can_continue":false"""))
        val blocked = notices(unavailable).filterIsInstance<Notice.Recovery>().single()
        assertTrue(!blocked.canContinue)
        assertEquals("rec-2", blocked.id, "a new recovery replaces the one it cannot continue")

        // `resuming` and `recovered` are transitions, not decisions: the notice goes away.
        for (status in listOf("resuming", "recovered", "dismissed")) {
            val after = reduce(unavailable, frame("recovery_state", """"recovery_id":"rec-2","status":"$status""""))
            assertTrue(
                notices(after).none { it is Notice.Recovery },
                "$status is not a decision the user makes; got ${notices(after)}",
            )
        }
    }

    @Test
    fun retryStatusShowsCountdown() {
        val waiting = reduce(
            state(),
            frame(
                "retry_status",
                """"state":"waiting","attempt":2,"max_attempts":4,"error_kind":"rate_limit","retry_after_s":3.0""",
            ),
            nowMs = 10_000,
        )
        val retry = notices(waiting).filterIsInstance<Notice.Retry>().single()
        assertEquals(2, retry.attempt)
        assertEquals(4, retry.maxAttempts)
        assertEquals("rate_limit", retry.errorKind)
        assertEquals(13_000L, retry.retryAtMs, "the countdown is the frame's own delay on the injected clock")

        for (settled in listOf("recovered", "cleared", "exhausted")) {
            val after = reduce(waiting, frame("retry_status", """"state":"$settled","attempt":2,"error_kind":"rate_limit""""))
            assertTrue(notices(after).none { it is Notice.Retry }, "$settled ends the countdown")
        }
    }

    @Test
    fun goalStripShowsTheObjectiveAndItsStatus() {
        val active = reduce(
            state(),
            frame("goal_state", """"goal_state":{"active":true,"status":"active","ui_summary":"3 of 5 checks","objective":"Ship the parser"}"""),
        )
        val goal = notices(active).filterIsInstance<Notice.Goal>().single()
        assertTrue(goal.active)
        assertEquals("active", goal.status)
        assertEquals("3 of 5 checks", goal.summary, "the strip shows ui_summary when the gateway sends one")

        // No summary: the objective is what the strip shows instead.
        val objectiveOnly = reduce(state(), frame("goal_state", """"goal_state":{"active":true,"status":"active","objective":"Ship the parser"}"""))
        assertEquals("Ship the parser", notices(objectiveOnly).filterIsInstance<Notice.Goal>().single().summary)

        // A goal the gateway reports as finished clears the strip rather than showing an old one.
        val finished = reduce(active, frame("goal_state", """"goal_state":{"active":false}"""))
        assertTrue(notices(finished).none { it is Notice.Goal })
    }

    @Test
    fun sandboxNoticeAppears() {
        val failed = reduce(
            state(),
            """{"event":"message","chat_id":"$chat","turn_id":"t1","tool_events":[
                 {"name":"exec","phase":"error","error_code":"sandbox_launcher_missing","call_id":"c1"}]}""",
        )
        val sandbox = notices(failed).filterIsInstance<Notice.Sandbox>().single()
        assertEquals(SandboxIssue.LauncherMissing, sandbox.issue)
        assertEquals("notice.sandbox.missing", sandbox.issue.copyKey)

        // `sandbox_path_not_absolute` and `sandbox_not_executable` are the same user problem.
        val path = reduce(
            state(),
            """{"event":"message","chat_id":"$chat","turn_id":"t1","tool_events":[
                 {"name":"exec","phase":"error","error_code":"sandbox_not_executable","call_id":"c1"}]}""",
        )
        assertEquals(SandboxIssue.Path, notices(path).filterIsInstance<Notice.Sandbox>().single().issue)

        // A tool that is not exec, or an error code outside the seven, says nothing about the sandbox.
        val unrelated = reduce(
            state(),
            """{"event":"message","chat_id":"$chat","turn_id":"t1","tool_events":[
                 {"name":"read_file","phase":"error","error_code":"sandbox_launcher_missing","call_id":"c1"}]}""",
        )
        assertTrue(notices(unrelated).none { it is Notice.Sandbox })
        val unknownCode = reduce(
            state(),
            """{"event":"message","chat_id":"$chat","turn_id":"t1","tool_events":[
                 {"name":"exec","phase":"error","error_code":"disk_full","call_id":"c1"}]}""",
        )
        assertTrue(notices(unknownCode).none { it is Notice.Sandbox })
    }

    @Test
    fun aSandboxWarningClearsWhenExecSucceedsOrANewTurnStarts() {
        val warned = reduce(
            state(),
            """{"event":"message","chat_id":"$chat","turn_id":"t1","tool_events":[
                 {"name":"exec","phase":"error","error_code":"sandbox_unprotected_installation","call_id":"c1"}]}""",
        )

        val ended = reduce(
            warned,
            """{"event":"message","chat_id":"$chat","turn_id":"t1","tool_events":[
                 {"name":"exec","phase":"end","call_id":"c2"}]}""",
        )
        assertTrue(notices(ended).none { it is Notice.Sandbox }, "a working exec retires the warning")

        val newTurn = reduce(warned, """{"event":"message_accepted","chat_id":"$chat","turn_id":"t2","starts_turn":true}""")
        assertTrue(notices(newTurn).none { it is Notice.Sandbox }, "a new turn starts clean")

        // `useSandboxNotice.ts:29` names both events; a replayed history row is the other one.
        val promptTurn = reduce(
            warned,
            """{"event":"user_message","chat_id":"$chat","turn_id":"t3","text":"try again","starts_turn":true}""",
        )
        assertTrue(notices(promptTurn).none { it is Notice.Sandbox }, "a prompt that starts a turn clears it too")
    }

    @Test
    fun aDismissedNoticeStaysDismissed() {
        val warned = reduce(
            state(),
            """{"event":"message","chat_id":"$chat","turn_id":"t1","tool_events":[
                 {"name":"exec","phase":"error","error_code":"sandbox_start_failed","call_id":"c1"}]}""",
        )
        val sandbox = notices(warned).filterIsInstance<Notice.Sandbox>().single()

        val dismissed = ThreadReducer.dismiss(warned, sandbox.id)

        assertNull(notices(dismissed).firstOrNull { it.id == sandbox.id })
    }

    @Test
    fun noticesDoNotDisturbTheAnswer() {
        // The precedence rule that matters: a notice never creates, settles or re-orders a turn.
        val streaming = reduce(state(), """{"event":"delta","chat_id":"$chat","turn_id":"t1","stream_id":"s","text":"hi"}""")
        val withNotices = reduce(
            streaming,
            frame("retry_status", """"state":"waiting","attempt":1,"error_kind":"timeout","retry_after_s":1"""),
        )
        assertEquals(streaming.turns.map { it.id }, withNotices.turns.map { it.id })
        assertEquals(streaming.turns.single().answer.text, withNotices.turns.single().answer.text)
    }
}
