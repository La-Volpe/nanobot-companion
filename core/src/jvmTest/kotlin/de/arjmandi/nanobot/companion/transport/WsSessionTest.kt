package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.DeltaFrame
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.ReadyFrame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame as KtorFrame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The socket session is where a single-use token and a frame stream meet. The server here is
 * a real Ktor WebSocket server in-process: the pump is tested against an actual socket, not a
 * fake flow.
 */
class WsSessionTest {

    /** The test host's own client, extended with the WebSocket plugin, so the URL resolves. */
    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    @Test
    fun connectsWithSingleUseToken() = testApplication {
        var query: String? = null
        install(ServerWebSockets)
        routing {
            webSocket("/ws") {
                query = call.request.queryParameters.entries().joinToString("&") { (key, values) -> "$key=${values.first()}" }
                send(KtorFrame.Text("""{"event":"ready","chat_id":"chat-1","client_id":"cli"}"""))
                close()
            }
        }

        val session = WsSession(wsClient())
        session.frames("ws://localhost/ws?token=single-use").toList()

        assertEquals("token=single-use", query, "the handshake carries the bootstrap token in the query (D15.5)")
    }

    @Test
    fun emitsReadyThenFrames() = testApplication {
        install(ServerWebSockets)
        routing {
            webSocket("/ws") {
                send(KtorFrame.Text("""{"event":"ready","chat_id":"chat-1","client_id":"cli"}"""))
                send(KtorFrame.Text("""{"event":"delta","chat_id":"chat-1","text":"po","turn_id":"turn-1"}"""))
                send(KtorFrame.Text("""{"event":"delta","chat_id":"chat-1","text":"ng","turn_id":"turn-1"}"""))
                close()
            }
        }

        val frames: List<Frame> = WsSession(wsClient()).connect("ws://localhost/ws?token=t").frames.toList()

        assertIs<ReadyFrame>(frames[0])
        assertEquals(listOf("po", "ng"), frames.drop(1).map { (it as DeltaFrame).text })
    }

    @Test
    fun closeEmitsClosed() = testApplication {
        install(ServerWebSockets)
        routing {
            webSocket("/ws") {
                send(KtorFrame.Text("""{"event":"ready","chat_id":"chat-1"}"""))
                close()
            }
        }

        val frames = mutableListOf<Frame>()
        WsSession(wsClient()).connect("ws://localhost/ws?token=t").frames.collect { frames += it }

        assertEquals(1, frames.size, "the flow completes when the server closes instead of hanging")
    }

    @Test
    fun framesAreDecodedNotRaw() = testApplication {
        install(ServerWebSockets)
        routing {
            webSocket("/ws") {
                send(KtorFrame.Text("""{"event":"telemetry_ping","future":true}"""))
                close()
            }
        }

        val frame = WsSession(wsClient()).connect("ws://localhost/ws?token=t").frames.toList().single()

        assertEquals("telemetry_ping", frame.event, "an unknown frame still reaches the engine")
    }
}
