package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The model has to be a value: two reductions of the same frames must be
 * equal, or a SwiftUI/Compose view cannot tell a redraw from a change, and nothing is
 * allowed to be mutated behind the StateFlow.
 */
class ThreadModelTest {

    private fun delta(text: String, streamId: String = "s1") = FrameCodec.decode(
        """{"event":"delta","chat_id":"chat-1","text":"$text","stream_id":"$streamId","turn_id":"turn-9"}""",
    )

    private fun replay(): ThreadState =
        listOf(delta("a"), delta("b"), delta("c")).fold(ThreadState(chatId = "chat-1")) { state, frame ->
            ThreadReducer.reduce(state, frame, nowMs = 0).state
        }

    @Test
    fun idsAreStableAcrossReductions() {
        val first = replay()
        val second = replay()

        assertEquals(first, second, "the same frames produce the same state, ids included")
        assertEquals(listOf("turn-9"), first.turns.map { it.id }, "the turn id is the gateway's turn id")
        assertEquals(listOf("s1"), first.turns.single().answer.segments.map { it.id })
        assertEquals("abc", first.turns.single().answer.text)
    }

    @Test
    fun reductionNeverMutatesTheInputState() {
        val before = ThreadState(chatId = "chat-1")
        val after = ThreadReducer.reduce(before, delta("a"), nowMs = 0).state

        assertTrue(before.turns.isEmpty(), "the previous state is still empty")
        assertNotSame(before, after)
        assertEquals(1, after.turns.size)
    }

    @Test
    fun activeTurnTracksTheLatestTurn() {
        val state = ThreadReducer.reduce(ThreadState(chatId = "chat-1"), delta("a"), 0).state

        assertEquals("turn-9", state.activeTurnId)
        assertEquals("turn-9", state.activeTurn?.id)
    }
}
