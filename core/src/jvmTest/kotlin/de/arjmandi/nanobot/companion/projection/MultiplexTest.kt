package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.FrameCodec
import de.arjmandi.nanobot.companion.wire.Outbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One socket serves every chat. The gateway tags each frame with its chat,
 * so the router's job is two rules: route by `chat_id`, and never let a send overtake the
 * attach that makes its chat real.
 */
class MultiplexTest {

    private val a = "websocket:a"
    private val b = "websocket:b"

    private fun delta(chatId: String, text: String, turnId: String = "turn-1") = FrameCodec.decode(
        """{"event":"delta","chat_id":"$chatId","text":"$text","stream_id":"s-$chatId","turn_id":"$turnId","turn_seq":1}""",
    )

    private fun attached(chatId: String) = FrameCodec.decode("""{"event":"attached","chat_id":"$chatId"}""")

    private fun message(chatId: String): Outbound.Message =
        Outbound.Message(chatId = chatId, turnId = "turn-1", content = "hi")

    private fun ChatRouted.after(vararg frames: de.arjmandi.nanobot.companion.wire.Frame): ChatRouted =
        frames.fold(this) { routed, frame -> ChatRouter.reduce(routed.set, frame, nowMs = 1_000) }

    @Test
    fun twoChatsShareOneSocket() {
        val routed = ChatRouter
            .reduce(ChatSet(attached = setOf(a, b)), delta(a, "alpha"), nowMs = 1_000)
            .after(delta(b, "beta"))

        assertEquals("alpha", routed.set.thread(a).turns.single().answer.text)
        assertEquals("beta", routed.set.thread(b).turns.single().answer.text, "each chat keeps its own turn")
        assertEquals(emptyList(), routed.effects.filterIsInstance<Effect.Send>())
    }

    @Test
    fun framesRouteByChatId() {
        val routed = ChatRouter
            .reduce(ChatSet(attached = setOf(a, b)), delta(a, "alpha"), nowMs = 1_000)
            .after(delta(b, "beta"))
            .after(FrameCodec.decode("""{"event":"turn_end","chat_id":"$a","turn_id":"turn-1"}"""))

        assertEquals(TurnStatus.Complete, routed.set.thread(a).turns.single().status, "a's turn settled")
        assertEquals(TurnStatus.Running, routed.set.thread(b).turns.single().status, "b's turn did not")
    }

    @Test
    fun sendOnUnattachedChatAutoAttaches() {
        val sent = ChatRouter.send(ChatSet(), chatId = a, message = message(a))

        val first = assertIs<Effect.Send>(sent.effects.first()).frame
        assertIs<Outbound.Attach>(first, "a send on a chat the gateway has not confirmed attaches first")
        assertEquals("websocket:a", first.chatId)
        assertEquals(
            listOf(message(a)),
            sent.set.pending[a],
            "the message waits for attached instead of overtaking it",
        )
        assertEquals(1, sent.effects.size, "only the attach goes out now")

        val confirmed = ChatRouter.reduce(sent.set, attached(a), nowMs = 1_000)

        val flushed = confirmed.effects.filterIsInstance<Effect.Send>().map { it.frame }
        assertEquals(listOf(message(a)), flushed, "attached releases the queued message")
        assertTrue(confirmed.set.pending.isEmpty(), "nothing is left waiting")
        assertTrue(a in confirmed.set.attached)

        val later = ChatRouter.send(confirmed.set, chatId = a, message = message(a))
        assertEquals(listOf<Effect>(Effect.Send(message(a))), later.effects, "an attached chat sends straight away")
    }

    /**
     * Opening a chat and sending on it in the same breath is one attach, not two. The second
     * attach would make the gateway replay the history twice, and the reducer would show every
     * row of it twice (`ChatRouter.open`'s own promise: "two attaches would double every row").
     */
    @Test
    fun openingThenSendingInTheSameBreathAttachesOnce() {
        val opened = ChatRouter.open(ChatSet(), chatId = a)
        assertIs<Outbound.Attach>(assertIs<Effect.Send>(opened.effects.single()).frame)

        val sent = ChatRouter.send(opened.set, chatId = a, message = message(a))

        assertTrue(
            sent.effects.isEmpty(),
            "the attach is already in flight, so the send only waits for it: ${sent.effects}",
        )
        assertEquals(listOf(message(a)), sent.set.pending[a], "and the message is queued behind that one attach")

        val confirmed = ChatRouter
            .reduce(sent.set, attached(a), nowMs = 1_000)
            .let { routed -> ChatRouter.reduce(routed.set, delta(a, "alpha"), nowMs = 1_000) }
        assertEquals("alpha", confirmed.set.thread(a).turns.single().answer.text)
        assertEquals(1, confirmed.set.thread(a).turns.size, "one prompt, one answer — not a replayed pair")
    }

    @Test
    fun frameWithoutChatIdIsNotGuessedAt() {
        val routed = ChatRouter.reduce(ChatSet(attached = setOf(a)), FrameCodec.decode("""{"event":"error","detail":"invalid chat_id"}"""), 0)

        assertNull(routed.unrouted?.let { null }, "a chat-less frame routes nowhere; the engine decides what to do with it")
        assertTrue(routed.effects.isEmpty())
        assertEquals(0, routed.set.threads.size, "a chat-less frame must not invent a thread")
    }
}
