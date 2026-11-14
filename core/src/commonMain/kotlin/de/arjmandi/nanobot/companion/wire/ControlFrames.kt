package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.JsonObject

/** The socket's greeting: it decides the chat, and it hands out the upload capability (D15.5). */
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

/** The gateway's default runtime changed; the model catalog should refresh (C6.2). */
internal data class RuntimeModelUpdatedFrame(
    val modelName: String?,
    val modelPreset: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "runtime_model_updated"
}

/** A soft protocol error: the connection stays open, the envelope was refused (C4.5). */
internal data class ErrorFrame(
    val detail: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "error"
}
