package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.fixture.Fixtures
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The streaming reducer. A live turn arrives as many `delta` frames with a
 * shared opaque `stream_id` and one `stream_end`; the reducer must build segments from
 * those ids alone, because arrival order alone cannot tell a resumed stream from a new one.
 */
class TurnReducerTest {

    private fun frame(text: String, streamId: String = "s1", seq: Int = 0) = FrameCodec.decode(
        """{"event":"delta","chat_id":"chat-1","text":"$text","stream_id":"$streamId","turn_id":"turn-1","turn_seq":$seq}""",
    )

    private fun end(streamId: String = "s1") = FrameCodec.decode(
        """{"event":"stream_end","chat_id":"chat-1","stream_id":"$streamId","turn_id":"turn-1"}""",
    )

    private fun reduce(vararg frames: Frame): ThreadState =
        frames.fold(ThreadState(chatId = "chat-1")) { state, frame -> ThreadReducer.reduce(state, frame, nowMs = 1_000).state }

    @Test
    fun appendsDeltasInOrder() {
        val state = reduce(frame("po", seq = 1), frame("ng", seq = 2))

        val answer = state.turns.single().answer
        assertEquals("pong", answer.text)
        assertEquals(1, answer.segments.size, "one stream id is one segment")
        assertEquals("s1", answer.segments.single().id, "the segment id is the gateway's stream id")
    }

    @Test
    fun streamEndClosesSegment() {
        val state = reduce(frame("po"), frame("ng"), end())

        val segment = state.turns.single().answer.segments.single()
        assertTrue(segment.closed, "stream_end closes the segment instead of adding text")
        assertEquals("pong", segment.text)
    }

    @Test
    fun lateDeltaAfterEndCreatesNewSegment() {
        val state = reduce(frame("po"), end(), frame("ng"))

        val segments = state.turns.single().answer.segments
        assertEquals(2, segments.size, "text after the end is a new segment, not appended to a closed one")
        assertEquals("po", segments[0].text)
        assertEquals("ng", segments[1].text)
        assertNotEquals(segments[0].id, segments[1].id, "two open segments cannot share an id")
    }

    @Test
    fun recordedTurnReplaysIntoOneAnswer() {
        val frames = Fixtures.wsFrames().map { FrameCodec.decode(it.toString()) }
        val state = frames.fold(ThreadState(chatId = "UUID-2")) { s, frame -> ThreadReducer.reduce(s, frame, 1_000).state }

        val turn = state.turns.single()
        assertEquals("turn-1", turn.id)
        assertEquals("pong", turn.answer.text, "the recorded delta's text is what the screen shows")
        assertTrue(turn.answer.segments.single().closed, "the recorded stream_end closed it")
        assertEquals(TurnStatus.Complete, turn.status, "the recorded turn_end settled the turn")
        assertEquals(5703, turn.usage?.promptTokens)
    }

    @Test
    fun unknownFrameChangesNothing() {
        val before = ThreadState(chatId = "chat-1")
        val reduction = ThreadReducer.reduce(before, FrameCodec.decode("""{"event":"telemetry_ping"}"""), 0)

        assertEquals(before, reduction.state, "a frame from a newer gateway must not disturb the thread")
        assertTrue(reduction.effects.isEmpty())
    }
}
