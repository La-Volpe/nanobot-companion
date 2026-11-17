package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.FrameCodec
import de.arjmandi.nanobot.companion.wire.Outbound
import de.arjmandi.nanobot.companion.wire.OutboundCodec
import de.arjmandi.nanobot.companion.wire.QuotedContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Quoting an earlier reply. Two halves, and both were wrong in the same
 * way, both written from the docs rather than from the WebUI:
 *
 * - The envelope's `quoted_context` is a plain string. The gateway's
 *   `normalize_webui_quote` returns `None` for anything that is not `str`, so the object
 *   this client used to send silently dropped every quote.
 * - The gateway does not echo the quote back on `user_message`, so the bubble keeps the
 *   quote the client put there and must not lose it when the ack and the prompt arrive.
 */
class QuotedContextTest {

    @Test
    fun quoteIsAStringOnTheWire() {
        val encoded = OutboundCodec.encode(
            Outbound.Message(
                chatId = "chat-1",
                turnId = "turn-2",
                content = "and this?",
                quotedContext = QuotedContext(text = "earlier message"),
            ),
        )

        assertEquals(
            "earlier message",
            encoded["quoted_context"]?.jsonPrimitive?.content,
            "the WebUI sends quoted_context as a string, and the gateway drops anything else",
        )
    }

    @Test
    fun quoteIsBoundedByTheGatewayLimit() {
        val long = "x".repeat(5_000)

        val encoded = OutboundCodec.encode(
            Outbound.Message(chatId = "chat-1", turnId = "turn-2", content = "q", quotedContext = QuotedContext.of(long)),
        )

        assertEquals(
            4_000,
            encoded["quoted_context"]?.jsonPrimitive?.content?.length,
            "the gateway truncates at 4000 characters; sending more is a silent loss",
        )
    }

    @Test
    fun quoteLandsOnUserMessage() {
        val accepted = FrameCodec.decode(
            """{"event":"message_accepted","chat_id":"chat-1","turn_id":"turn-2","active_turn_id":"turn-2"}""",
        )
        val prompt = FrameCodec.decode(
            """{"event":"user_message","chat_id":"chat-1","turn_id":"turn-2","text":"and this?","starts_turn":false}""",
        )
        val optimistic = ThreadState(
            chatId = "chat-1",
            turns = listOf(
                Turn(
                    id = "turn-2",
                    prompt = UserPrompt(text = "and this?", quote = "earlier message", optimistic = true),
                ),
            ),
            activeTurnId = "turn-2",
        )

        val state = listOf(accepted, prompt).fold(optimistic) { current, frame ->
            ThreadReducer.reduce(current, frame, nowMs = 1_000).state
        }

        val userPrompt = state.turn("turn-2")!!.prompt
        assertIs<UserPrompt>(userPrompt)
        assertEquals("and this?", userPrompt.text)
        assertEquals("earlier message", userPrompt.quote, "the quote is the client's, so the ack must not erase it")
        assertFalse(userPrompt.optimistic, "message_accepted still settles the bubble (C4.5)")
    }
}
