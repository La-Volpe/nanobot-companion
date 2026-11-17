package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.JsonObject

/** The socket's greeting: it decides the chat, and it hands out the upload capability. */
internal data class ReadyFrame(
    val clientId: String?,
    val upload: UploadCapability?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "ready"
}

/** Short-lived per-connection capability for `POST /api/attachments`; never persisted. */
internal data class UploadCapability(val path: String, val token: String)

internal data class AttachedFrame(
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "attached"
}

internal data class PairingChangedFrame(
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "pairing_changed"
}

/** The gateway's default runtime changed; the model catalog should refresh. */
internal data class RuntimeModelUpdatedFrame(
    val modelName: String?,
    val modelPreset: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "runtime_model_updated"
}

/** `transcribe_audio` out, this back: `{request_id, text}` (`transcription_ws.py:51`). */
internal data class TranscriptionResultFrame(
    val requestId: String?,
    val text: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "transcription_result"
}

/** A refused recording: `{detail, request_id}` on its own event, not on `error`. */
internal data class TranscriptionErrorFrame(
    val requestId: String?,
    val detail: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "transcription_error"
}

/**
 * The answer to a `webui_request`, the socket call an automation action goes out on.
 * `ok:false` carries `error{status, message}` from the
 * HTTP route the mutation maps onto, so a refused automation action keeps the gateway's own
 * status and words (`inbound_commands.py:1219-1245`).
 */
internal data class WebuiResponseFrame(
    val requestId: String?,
    val ok: Boolean,
    val result: kotlinx.serialization.json.JsonElement?,
    val errorStatus: Int?,
    val errorMessage: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "webui_response"
}

/** A soft protocol error: the connection stays open, the envelope was refused. */
internal data class ErrorFrame(
    val detail: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "error"
}
