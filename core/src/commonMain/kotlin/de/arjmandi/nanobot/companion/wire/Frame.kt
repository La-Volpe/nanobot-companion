package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.JsonObject

/**
 * One server frame. Every frame keeps its original [raw] payload, so a field added by a
 * newer gateway survives a decode/encode round trip even though this client has no
 * property for it.
 */
internal sealed interface Frame {
    val event: String

    /** Which chat the frame belongs to; null only for frames the gateway sends before one exists. */
    val chatId: String?

    val raw: JsonObject
}

/**
 * A frame this client does not model: a newer gateway's event, or a payload whose shape
 * changed. It is never an exception, because a client that throws on the future stops showing
 * the chat the user is in the middle of.
 */
internal class UnknownFrame(
    override val event: String,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame
