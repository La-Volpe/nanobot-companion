package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.FrameCodec
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame as KtorFrame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One socket, one frame stream (plan.md C3.3). The flow is cold: collecting it opens the
 * connection, so an abandoned subscription cannot leave a socket behind, and completing
 * it closes the socket. The URL already carries the single-use token, because the token
 * is consumed by the handshake itself.
 */
internal class WsSession(private val http: HttpClient) {

    /**
     * A socket that can be written to. The write handle is only usable once the flow is
     * being collected — the engine starts its pump before anything can be sent, so awaiting
     * here is a no-op in practice rather than a place to hang.
     */
    fun connect(url: String): Socket {
        val opened = CompletableDeferred<DefaultClientWebSocketSession>()
        val frames = flow {
            val socket = http.webSocketSession(url)
            opened.complete(socket)
            try {
                for (frame in socket.incoming) {
                    if (frame is KtorFrame.Text) emit(FrameCodec.decode(frame.readText()))
                }
            } finally {
                socket.close()
            }
        }
        return object : Socket {
            override val frames: Flow<Frame> = frames
            override suspend fun send(text: String) {
                opened.await().send(KtorFrame.Text(text))
            }
        }
    }

    /** The read half on its own, for a caller that only follows a chat. */
    fun frames(url: String): Flow<Frame> = connect(url).frames
}
