package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.FrameCodec
import de.arjmandi.nanobot.companion.wire.Outbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Opening a chat (plan.md C5.3). The gateway mints ids, so the client never invents one: an
 * existing chat is attached by the id it was opened with, and a new chat learns its id from
 * the `attached` frame. Everything the client sends leaves as an [Effect.Send] so these
 * tests need no socket.
 */
class ChatLifecycleTest {

    private fun attached(chatId: String) =
        FrameCodec.decode("""{"event":"attached","chat_id":"$chatId"}""")

    private fun error(detail: String) = FrameCodec.decode("""{"event":"error","detail":"$detail"}""")

    @Test
    fun attachOnOpenExisting() {
        val opened = ChatLifecycle.open("websocket:abc")

        assertEquals(ChatLifecycleState.Phase.Attaching, opened.state.phase, "an open is not done until the gateway answers")
        val sent = assertIs<Effect.Send>(opened.effects.single()).frame
        assertIs<Outbound.Attach>(sent)
        assertEquals("websocket:abc", sent.chatId, "an existing chat is attached by the id the caller opened")

        val done = ChatLifecycle.reduce(opened.state, attached("websocket:abc"))

        assertEquals(ChatLifecycleState.Phase.Attached, done.state.phase)
        assertEquals("websocket:abc", done.state.chatId)
        assertNull(done.state.failure)
        assertEquals(emptyList(), done.effects, "attaching needs no follow-up frame")
    }

    @Test
    fun newChatMintsIdFromAttached() {
        val opened = ChatLifecycle.openNew()

        assertEquals(null, opened.state.chatId, "a new chat has no id until the gateway mints one")
        assertIs<Outbound.NewChat>(assertIs<Effect.Send>(opened.effects.single()).frame)

        val done = ChatLifecycle.reduce(opened.state, attached("websocket:minted"))

        assertEquals("websocket:minted", done.state.chatId, "the id is the gateway's, taken from attached")
        assertEquals(ChatLifecycleState.Phase.Attached, done.state.phase)
    }

    @Test
    fun openUnknownChatSurfacesError() {
        val opened = ChatLifecycle.open("websocket:gone")

        val failed = ChatLifecycle.reduce(opened.state, error("unknown chat"))

        assertEquals(ChatLifecycleState.Phase.NotOpen, failed.state.phase, "a rejected attach leaves the chat closed")
        assertEquals("unknown chat", failed.state.failure, "the gateway's reason is what the screen shows")
        assertNull(failed.state.chatId.takeIf { failed.state.phase == ChatLifecycleState.Phase.Attached })
    }

    @Test
    fun errorAfterAttachDoesNotCloseTheChat() {
        val opened = ChatLifecycle.open("websocket:abc")
        val done = ChatLifecycle.reduce(opened.state, attached("websocket:abc"))

        val noise = ChatLifecycle.reduce(done.state, error("invalid chat_id"))

        assertEquals(done.state, noise.state, "a later rejection is about a message, not about the chat being open")
    }
}
