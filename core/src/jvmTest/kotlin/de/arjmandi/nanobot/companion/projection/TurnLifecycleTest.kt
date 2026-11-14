package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.fixture.Fixtures
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delivery and settle (plan.md C4.3, C4.5): the optimistic bubble, the reasoning bubble,
 * and the moment the stop button has to become a send button again.
 */
class TurnLifecycleTest {

    private fun decode(text: String): Frame = FrameCodec.decode(text)

    private fun optimisticState(): ThreadState = ThreadState(
        chatId = "chat-1",
        turns = listOf(Turn(id = "turn-1", prompt = UserPrompt(text = "hi", optimistic = true), startedAtMs = 900)),
        activeTurnId = "turn-1",
    )

    @Test
    fun messageAcceptedReconcilesOptimisticBubble() {
        val reduction = ThreadReducer.reduce(
            optimisticState(),
            decode("""{"event":"message_accepted","chat_id":"chat-1","turn_id":"turn-1","starts_turn":true}"""),
            nowMs = 1_000,
        )

        val prompt = reduction.state.turns.single().prompt!!
        assertFalse(prompt.optimistic, "the gateway took the message, so the bubble is real")
        assertEquals("turn-1", reduction.state.activeTurnId)
        assertTrue(
            reduction.effects.contains(Effect.PersistCache("chat-1")),
            "the accepted state is worth caching; the cache is what a relaunch shows",
        )
    }

    /**
     * The history page replays reasoning as one `reasoning_end` per block carrying the block's
     * whole text (no `reasoning_delta` at all), while the socket streams deltas and ends with a
     * text-less `reasoning_end`. Both must produce a reasoning bubble — the recorded `file`
     * scenario's history page proves the shape.
     */
    @Test
    fun reasoningEndFromHistoryCarriesTheWholeBlock() {
        val replayed = reduceFrames(
            ThreadState(chatId = "chat-1"),
            """{"event":"reasoning_end","chat_id":"chat-1","turn_id":"t1","text":"Verify contents."}""",
            """{"event":"reasoning_end","chat_id":"chat-1","turn_id":"t1","text":"Say done."}""",
        )

        val reasoning = replayed.turn("t1")!!.answer.reasoning
        assertEquals(listOf("Verify contents.", "Say done."), reasoning.map { it.text }, "each block lands once")
        assertTrue(reasoning.all { it.closed }, "a reasoning_end block is finished, not streaming")
        assertTrue(replayed.turn("t1")!!.answer.reasoningDone)
    }

    /**
     * The live socket emits `stream_end` with `text: ""` for a stream that never carried a delta
     * — a placeholder the history page does not store. Rendering it gives the turn an empty
     * bubble, which is exactly the mismatch `core-cli watch --verify` printed (D37).
     */
    @Test
    fun streamEndWithAnEmptyTextCreatesNoBubble() {
        val state = reduceFrames(
            ThreadState(chatId = "chat-1"),
            """{"event":"stream_end","chat_id":"chat-1","stream_id":"placeholder","turn_id":"t1","text":""}""",
            """{"event":"delta","chat_id":"chat-1","stream_id":"s2","turn_id":"t1","text":"done"}""",
        )

        assertEquals(listOf("done"), state.turn("t1")!!.answer.segments.map { it.text })
    }

    @Test
    fun turnEndSettlesStopButton() {
        val running = reduceFrames(
            ThreadState(chatId = "chat-1"),
            """{"event":"delta","chat_id":"chat-1","text":"po","stream_id":"s1","turn_id":"turn-1"}""",
            """{"event":"stream_end","chat_id":"chat-1","stream_id":"s1","turn_id":"turn-1"}""",
            """{"event":"turn_end","chat_id":"chat-1","turn_id":"turn-1","latency_ms":404,
                "usage":{"prompt_tokens":10,"completion_tokens":2,"context_tokens":10},
                "goal_state":{"active":false},"context_window_tokens":32768}""",
        )

        val turn = running.turns.single()
        assertEquals(TurnStatus.Complete, turn.status)
        assertNull(running.activeTurnId, "nothing is running, so the composer offers send again")
        assertEquals(32_768, running.contextWindowTokens, "the usage ring has its window (C6.7)")
        assertEquals(10, running.usage?.promptTokens)
    }

    @Test
    fun failedTurnEndMarksTheTurnFailed() {
        val state = reduceFrames(
            ThreadState(chatId = "chat-1"),
            """{"event":"message_accepted","chat_id":"chat-1","turn_id":"turn-1","starts_turn":true}""",
            """{"event":"turn_end","chat_id":"chat-1","turn_id":"turn-1","failure_kind":"provider_error",
                "failure_error_kind":"network","failure_message":"upstream refused"}""",
        )

        assertEquals(TurnStatus.Failed, state.turns.single().status)
        assertEquals("provider_error", state.turns.single().failureKind, "the retry affordance needs the kind")
        assertNull(state.activeTurnId)
    }

    @Test
    fun errorFrameSurfacesAsANotice() {
        val state = reduceFrames(
            ThreadState(chatId = "chat-1"),
            """{"event":"error","chat_id":"chat-1","detail":"message_rejected"}""",
        )

        assertTrue(
            state.notices.any { it is Notice.Failure && (it as Notice.Failure).errorKind == "message_rejected" },
            "a rejected envelope is shown, not swallowed: ${state.notices}",
        )
    }

    @Test
    fun reasoningBubbleCollapsesOnReasoningEnd() {
        val state = reduceFrames(
            ThreadState(chatId = "chat-1"),
            """{"event":"reasoning_delta","chat_id":"chat-1","text":"let me ","stream_id":"r1","turn_id":"turn-1"}""",
            """{"event":"reasoning_delta","chat_id":"chat-1","text":"think","stream_id":"r1","turn_id":"turn-1"}""",
            """{"event":"reasoning_end","chat_id":"chat-1","stream_id":"r1","turn_id":"turn-1"}""",
        )

        val answer = state.turns.single().answer
        assertEquals("let me think", answer.reasoning.single().text)
        assertTrue(answer.reasoning.single().closed, "the bubble locks when reasoning ends")
        assertTrue(answer.reasoningDone)
    }

    @Test
    fun reasoningWithoutAnswerStillEndsTurn() {
        val state = reduceFrames(
            ThreadState(chatId = "chat-1"),
            """{"event":"reasoning_delta","chat_id":"chat-1","text":"hmm","stream_id":"r1","turn_id":"turn-1"}""",
            """{"event":"reasoning_end","chat_id":"chat-1","stream_id":"r1","turn_id":"turn-1"}""",
            """{"event":"turn_end","chat_id":"chat-1","turn_id":"turn-1","usage":{"prompt_tokens":5}}""",
        )

        assertEquals(TurnStatus.Complete, state.turns.single().status)
        assertEquals("", state.turns.single().answer.text, "an empty answer is still a finished turn")
    }

    private fun reduceFrames(initial: ThreadState, vararg frames: String): ThreadState =
        frames.map(::decode).fold(initial) { state, frame -> ThreadReducer.reduce(state, frame, nowMs = 1_000).state }
}
