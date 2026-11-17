package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.Outbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the client may send while a turn is running. Stop is `/stop` as a
 * message, steering is a second message on the same turn, and the queue is the client's own
 * because the gateway cannot hold a prompt for later.
 */
class StopSteerTest {

    private val chat = "websocket:a"
    private val running = "turn-1"

    private fun sent(reduction: ControlReduction): List<Outbound.Message> =
        reduction.effects.filterIsInstance<Effect.Send>().map { assertIs<Outbound.Message>(it.frame) }

    @Test
    fun stopSendsSlashStopOnce() {
        val first = TurnControl.stop(TurnControlState(), chatId = chat, activeTurnId = running)

        val stop = sent(first).single()
        assertEquals("/stop", stop.content, "stop is the text /stop; the gateway has no stop frame")
        assertEquals(chat, stop.chatId)
        assertEquals(running, stop.turnId, "the stop names the turn it is stopping")

        val again = TurnControl.stop(first.state, chatId = chat, activeTurnId = running)
        assertTrue(again.effects.isEmpty(), "a second tap while the stop is in flight sends nothing")
        assertEquals(first.state, again.state)

        // A different turn is a different stop.
        assertEquals(1, sent(TurnControl.stop(first.state, chatId = chat, activeTurnId = "turn-2")).size)
    }

    @Test
    fun steerWhileRunningReusesTurn() {
        val steered = TurnControl.steer(TurnControlState(), chatId = chat, activeTurnId = running, text = "also check the tests")

        val message = sent(steered).single()
        assertEquals("also check the tests", message.content)
        assertEquals(running, message.turnId, "steering joins the running turn instead of starting a new one")
        assertTrue(steered.state.queued.isEmpty(), "steering is not queued")
    }

    @Test
    fun queueHoldsOnePromptAndFlushesOnTurnEnd() {
        val one = TurnControl.enqueue(TurnControlState(), chatId = chat, text = "first", capacity = 1)

        assertEquals(listOf(QueuedPrompt(chat, "first")), one.state.queued)
        assertTrue(one.effects.isEmpty(), "a queued prompt waits for the turn to end")

        val full = TurnControl.enqueue(one.state, chatId = chat, text = "second", capacity = 1)
        assertEquals(listOf(QueuedPrompt(chat, "first")), full.state.queued, "the design shows one queued prompt, so a second is refused")
        assertTrue(full.effects.isEmpty())

        val flushed = TurnControl.onTurnEnd(one.state, chatId = chat, turnId = running)
        assertEquals("first", sent(flushed).single().content, "the settled turn releases the queued prompt")
        assertTrue(flushed.state.queued.isEmpty())

        val protocol = TurnControl.enqueue(TurnControlState(), chatId = chat, text = "p")
        assertEquals(TurnControl.MAX_QUEUE, 20, "the protocol's own ceiling is twenty")
        assertEquals(1, protocol.state.queued.size)
    }
}
