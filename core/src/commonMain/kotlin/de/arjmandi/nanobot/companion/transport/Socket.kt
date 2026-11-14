package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.Frame
import kotlinx.coroutines.flow.Flow

/**
 * One open WebSocket: the frames arriving, and the only way to write one back. A [Flow]
 * alone is a read path, and the engine needs both halves on the same socket — a write over
 * a second connection would be a second handshake, and the single-use token pays for one.
 */
internal interface Socket {
    val frames: Flow<Frame>

    suspend fun send(text: String)
}
