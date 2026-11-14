package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The client → server envelopes (plan.md C2.3). One builder per envelope, no string
 * concatenation anywhere: a field name here is the gateway's inbound contract, and the
 * shapes are read from `nanobot/webui/inbound_commands.py` and `forking.py`.
 */
internal sealed interface Outbound {
    val type: String

    data object NewChat : Outbound {
        override val type: String get() = "new_chat"
    }

    data class Attach(val chatId: String) : Outbound {
        override val type: String get() = "attach"
    }

    data class Message(
        val chatId: String,
        val turnId: String,
        val content: String,
        val media: List<UploadReference> = emptyList(),
        val quotedContext: QuotedContext? = null,
        val intent: String? = null,
        val userShell: Boolean = false,
    ) : Outbound {
        override val type: String get() = "message"
    }

    data class SetWorkspaceScope(
        val chatId: String,
        val accessMode: String,
        val projectPath: String? = null,
        val restrictToWorkspace: Boolean? = null,
    ) : Outbound {
        override val type: String get() = "set_workspace_scope"
    }

    data class SetSidebarState(val state: JsonObject) : Outbound {
        override val type: String get() = "set_sidebar_state"
    }

    data class TranscribeAudio(
        val requestId: String,
        val audio: ByteArray,
        val durationMs: Long,
        val mimeType: String = "audio/wav",
    ) : Outbound {
        override val type: String get() = "transcribe_audio"
    }

    data class ForkChat(val sourceChatId: String, val beforeUserIndex: Int, val title: String? = null) : Outbound {
        override val type: String get() = "fork_chat"
    }

    data object NewTemporaryChat : Outbound {
        override val type: String get() = "new_temporary_chat"
    }

    data class DiscardTemporaryChat(val chatId: String) : Outbound {
        override val type: String get() = "discard_temporary_chat"
    }

    data class WebuiRequest(val requestId: String, val action: String, val payload: JsonObject) : Outbound {
        override val type: String get() = "webui_request"
    }
}

/** The reference an upload returns; the message carries the reference, not the bytes (C6.6). */
internal data class UploadReference(val reference: String, val name: String? = null)

/**
 * A quoted excerpt of an earlier reply (C4.9). It goes out as a plain string on the
 * envelope — the gateway's `normalize_webui_quote` ignores anything else — and the gateway
 * uses it as model-only context, so the bubble's quote stays a client-side fact.
 */
internal data class QuotedContext(val text: String) {

    internal companion object {
        /** The gateway keeps 4000 characters and truncates silently, so the client does it visibly. */
        const val MAX_CHARS: Int = 4_000

        fun of(text: String): QuotedContext? {
            val normalised = text.replace("\r\n", "\n").replace('\r', '\n').trim()
            return normalised.takeIf { it.isNotEmpty() }?.let { QuotedContext(it.take(MAX_CHARS)) }
        }
    }
}

internal object OutboundCodec {

    fun encode(frame: Outbound): JsonObject = when (frame) {
        is Outbound.NewChat -> objectOf("type" to "new_chat")
        is Outbound.Attach -> objectOf("type" to "attach", "chat_id" to frame.chatId)
        is Outbound.Message -> message(frame)
        is Outbound.SetWorkspaceScope -> workspaceScope(frame)
        is Outbound.SetSidebarState -> buildJsonObject {
            put("type", "set_sidebar_state")
            put("state", frame.state)
        }
        is Outbound.TranscribeAudio -> objectOf(
            "type" to "transcribe_audio",
            "request_id" to frame.requestId,
            "data_url" to "data:${frame.mimeType};base64," + base64(frame.audio),
            "duration_ms" to frame.durationMs,
        )
        is Outbound.ForkChat -> objectOf(
            "type" to "fork_chat",
            "source_chat_id" to frame.sourceChatId,
            "before_user_index" to frame.beforeUserIndex,
        ).let { forked -> frame.title?.let { JsonObject(forked + ("title" to JsonPrimitive(it))) } ?: forked }
        is Outbound.NewTemporaryChat -> objectOf("type" to "new_temporary_chat")
        is Outbound.DiscardTemporaryChat -> objectOf("type" to "discard_temporary_chat", "chat_id" to frame.chatId)
        is Outbound.WebuiRequest -> buildJsonObject {
            put("type", "webui_request")
            put("request_id", frame.requestId)
            put("action", frame.action)
            put("payload", frame.payload)
        }
    }

    private fun message(frame: Outbound.Message): JsonObject = buildJsonObject {
        put("type", "message")
        put("chat_id", frame.chatId)
        put("content", frame.content)
        // Without webui:true the gateway treats the frame as a legacy text message and
        // never sends message_accepted, which the optimistic bubble waits for.
        put("webui", true)
        put("turn_id", frame.turnId)
        if (frame.media.isNotEmpty()) {
            put(
                "media",
                JsonArray(
                    frame.media.map { reference ->
                        buildJsonObject {
                            put("reference", reference.reference)
                            reference.name?.let { put("name", it) }
                        }
                    },
                ),
            )
        }
        // A string, not an object: the WebUI's own client sends the trimmed excerpt and the
        // gateway's normalizer rejects every other type (C4.9, runtime_context.py).
        frame.quotedContext?.let { quote -> put("quoted_context", quote.text) }
        frame.intent?.let { put("intent", it) }
        if (frame.userShell) put("user_shell", true)
    }

    private fun workspaceScope(frame: Outbound.SetWorkspaceScope): JsonObject = buildJsonObject {
        put("type", "set_workspace_scope")
        put("chat_id", frame.chatId)
        put("access_mode", frame.accessMode)
        frame.projectPath?.let { put("project_path", it) }
        frame.restrictToWorkspace?.let { put("restrict_to_workspace", it) }
    }

    private fun objectOf(vararg pairs: Pair<String, Any>): JsonObject =
        JsonObject(
            pairs.associate { (key, value) ->
                key to when (value) {
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            },
        )

    /** No kotlin.io.encoding dependency in commonMain: base64 is small enough to own here. */
    private fun base64(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else -1
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else -1
            out.append(table[b0 shr 2])
            out.append(table[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            out.append(if (b1 >= 0) table[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            out.append(if (b2 >= 0) table[b2 and 0x3F] else '=')
            index += 3
        }
        return out.toString()
    }
}
