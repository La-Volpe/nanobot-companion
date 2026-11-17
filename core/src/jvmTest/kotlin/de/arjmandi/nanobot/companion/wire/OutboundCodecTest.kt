package de.arjmandi.nanobot.companion.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ten envelopes the client may send. Their shape is the
 * gateway's inbound contract (`nanobot/webui/inbound_commands.py`), so a wrong field
 * name here is a silent refusal on the wire, not a compile error.
 */
class OutboundCodecTest {

    private fun JsonObject.obj(name: String): JsonObject = this[name] as JsonObject

    private fun JsonObject.text(name: String): String = (this[name] as JsonPrimitive).content

    @Test
    fun messageCarriesWebuiFlagAndTurnId() {
        val encoded = OutboundCodec.encode(
            Outbound.Message(
                chatId = "chat-1",
                turnId = "turn-1",
                content = "Reply with exactly: pong",
            ),
        )

        assertEquals("message", encoded.text("type"))
        assertEquals("chat-1", encoded.text("chat_id"))
        assertEquals("turn-1", encoded.text("turn_id"), "the turn id is what makes message_accepted an ack")
        assertEquals("Reply with exactly: pong", encoded.text("content"))
        assertEquals(true, (encoded["webui"] as JsonPrimitive).booleanOrNull, "webui:true is what gets an ack at all")
        assertNull(encoded["media"], "no attachments means no media key")
    }

    @Test
    fun messageEncodesQuotedContextMediaAndIntent() {
        val encoded = OutboundCodec.encode(
            Outbound.Message(
                chatId = "chat-1",
                turnId = "turn-2",
                content = "and this?",
                media = listOf(UploadReference(reference = "ref-1", name = "report.pdf")),
                quotedContext = QuotedContext(text = "earlier message"),
            ),
        )

        val media = (encoded["media"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals("ref-1", media.text("reference"))
        assertEquals("report.pdf", media.text("name"))
        // A string, not an object: webui/src/lib/types.ts:1866 and the gateway's
        // normalize_webui_quote. This assertion previously expected an object and
        // was wrong, which is how the quote was silently lost.
        assertEquals("earlier message", (encoded["quoted_context"] as JsonPrimitive).content)
    }

    @Test
    fun setWorkspaceScopeShapeMatchesFixture() {
        val encoded = OutboundCodec.encode(
            Outbound.SetWorkspaceScope(chatId = "chat-1", accessMode = "full", projectPath = "/tmp/ws"),
        )

        assertEquals("set_workspace_scope", encoded.text("type"))
        assertEquals("chat-1", encoded.text("chat_id"))
        assertEquals("full", encoded.text("access_mode"), "access_mode is default|full (plan.md §0)")
        assertEquals("/tmp/ws", encoded.text("project_path"))
        assertTrue(
            encoded["restrict_to_workspace"] == null,
            "the client only sends restrict_to_workspace when the user sets it",
        )
    }

    @Test
    fun transcribeAudioUsesDataUrl() {
        val encoded = OutboundCodec.encode(
            Outbound.TranscribeAudio(requestId = "req-1", audio = byteArrayOf(1, 2, 3), durationMs = 900),
        )

        assertEquals("transcribe_audio", encoded.text("type"))
        assertEquals("req-1", encoded.text("request_id"), "the request id is how the result finds its draft")
        assertEquals("data:audio/wav;base64,AQID", encoded.text("data_url"))
        assertEquals("900", encoded.text("duration_ms"))
    }

    @Test
    fun chatLifecycleEnvelopesCarryOnlyTheirOwnKeys() {
        assertEquals(buildJsonObject { put("type", "new_chat") }, OutboundCodec.encode(Outbound.NewChat))
        assertEquals(
            buildJsonObject { put("type", "attach"); put("chat_id", "chat-1") },
            OutboundCodec.encode(Outbound.Attach("chat-1")),
        )
        assertEquals(
            buildJsonObject { put("type", "fork_chat"); put("source_chat_id", "chat-1"); put("before_user_index", 2) },
            OutboundCodec.encode(Outbound.ForkChat(sourceChatId = "chat-1", beforeUserIndex = 2)),
            "fork names the source chat and the prompt index, not the new chat",
        )
        assertEquals(
            buildJsonObject { put("type", "new_temporary_chat") },
            OutboundCodec.encode(Outbound.NewTemporaryChat),
        )
        assertEquals(
            buildJsonObject { put("type", "discard_temporary_chat"); put("chat_id", "chat-1") },
            OutboundCodec.encode(Outbound.DiscardTemporaryChat("chat-1")),
        )
    }

    @Test
    fun sidebarStateGoesAsOneNestedObject() {
        val encoded = OutboundCodec.encode(
            Outbound.SetSidebarState(
                buildJsonObject {
                    put("pinned", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("chat-1"))))
                    put("archived", kotlinx.serialization.json.JsonArray(emptyList()))
                },
            ),
        )

        assertEquals("set_sidebar_state", encoded.text("type"))
        assertTrue(encoded["pinned"] == null, "the state is nested, not spread over the envelope")
        assertEquals("chat-1", ((encoded.obj("state")["pinned"] as kotlinx.serialization.json.JsonArray).single() as JsonPrimitive).content)
    }

    @Test
    fun webuiRequestNamesTheActionAndRequest() {
        val encoded = OutboundCodec.encode(
            Outbound.WebuiRequest(
                requestId = "req-1",
                action = "update.check",
                payload = buildJsonObject { put("channel", "stable") },
            ),
        )

        assertEquals("webui_request", encoded.text("type"))
        assertEquals("req-1", encoded.text("request_id"))
        assertEquals("update.check", encoded.text("action"))
        assertEquals("stable", encoded.obj("payload").text("channel"))
    }
}
