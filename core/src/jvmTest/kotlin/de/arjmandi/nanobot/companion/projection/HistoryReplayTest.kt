package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.fixture.Fixtures
import de.arjmandi.nanobot.companion.wire.FrameCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The parity claim the whole client rests on: history and the live
 * socket speak the same vocabulary, so replaying the recorded history page must produce
 * the same thread a user watched arrive on the socket.
 */
class HistoryReplayTest {

    private fun liveState(): ThreadState =
        Fixtures.wsFrames()
            .map { FrameCodec.decode(it.toString()) }
            .fold(ThreadState(chatId = "UUID-2")) { state, frame ->
                ThreadReducer.reduce(state, frame, nowMs = 0).state
            }

    /** The public replay the apps and `core-cli watch --verify` use, not a test-only copy. */
    private fun replayedState(): ThreadState =
        HistoryReplay.fromPage(File(Fixtures.latest, "history.json").readText(), chatId = "UUID-2")

    @Test
    fun replayedHistoryEqualsLiveState() {
        val live = liveState()
        val replayed = replayedState()

        assertEquals(live.turns.map { it.id }, replayed.turns.map { it.id }, "the same turn, from both feeds")
        assertEquals(live.turns.single().answer.text, replayed.turns.single().answer.text, "the same answer text")
        assertEquals(live.turns.single().status, replayed.turns.single().status, "the same settle state")
        assertEquals(live.usage?.promptTokens, replayed.usage?.promptTokens, "the same usage")
        assertEquals(live.contextWindowTokens, replayed.contextWindowTokens)
    }

    @Test
    fun historyPromptLandsOnTheUserMessage() {
        val replayed = replayedState()

        assertEquals("Reply with exactly: pong", replayed.turns.single().prompt?.text)
        assertEquals(false, replayed.turns.single().prompt?.optimistic, "replayed prompts are never optimistic")
    }
}
