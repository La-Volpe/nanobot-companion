package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.FrameCodec
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame as KtorFrame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One socket, one frame stream (plan.md C3.3). The flow is cold: collecting it opens the
 * connection, so an abandoned subscription cannot leave a socket behind, and completing
 * it closes the socket. The URL already carries the single-use token, because the token
 * is consumed by the handshake itself.
 */
internal class WsSession(private val http: HttpClient) {

    fun frames(url: String): Flow<Frame> = flow {
        val socket = http.webSocketSession(url)
        try {
            for (frame in socket.incoming) {
                if (frame is KtorFrame.Text) emit(FrameCodec.decode(frame.readText()))
            }
        } finally {
            socket.close()
        }
    }
}
