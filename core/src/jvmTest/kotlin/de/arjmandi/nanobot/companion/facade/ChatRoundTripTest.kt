package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.projection.TurnStatus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame as KtorFrame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The whole path in one test: bootstrap, handshake, `ready`, send, stream, `turn_end`
 * (plan.md C8.1, G1's `-Pcontract` in miniature). The gateway here is a real Ktor WebSocket
 * server in-process, so what is exercised is the client's own HTTP and socket code rather
 * than a fake of it.
 */
class ChatRoundTripTest {

    private val chat = "websocket:round-trip"

    private fun bootstrap(token: String): String = """
        {"token":"$token","ws_url":"ws://localhost/ws","ws_path":"/ws","expires_in":300,
         "model_name":"deepseek-v4.1-flash:cloud","api_token":"api-token",
         "terminal":{"webui":{"version":"0.4.1","min_protocol":1,"max_protocol":1,
           "capabilities":["webui.core.v1","webui.attachments.binary.v1"]}},
         "runtime_capabilities":{"can_restart_engine":false,"can_open_logs":false,"can_export_diagnostics":false}}
    """.trimIndent()

    private val sessions = """
        {"sessions":[{"key":"$chat","title":"Round trip","preview":"pong","model_preset":"default",
          "created_at":"2026-11-14T03:00:00.000000","updated_at":"2026-11-14T03:01:00.000000",
          "workspace_scope":{"access_mode":"default"}}]}
    """.trimIndent()

    private fun ApplicationTestBuilder.client(): HttpClient = createClient { install(WebSockets) }

    private fun deps(): CoreDeps = CoreDeps(
        clock = FakeClock(startMs = 1_000),
        secureStore = InMemorySecureStore(),
        files = InMemoryFileStore(),
        appInfo = FakeAppInfo(),
        // Real dispatchers: this test drives real in-process I/O, not virtual time.
        dispatchers = CoreDispatchers(compute = Dispatchers.Default, main = Dispatchers.Default),
    )

    @Test
    fun bootstrapConnectSendStreamAndSettle() = testApplication {
        val tokens = AtomicInteger()
        install(ServerWebSockets)
        routing {
            get("/webui/bootstrap") {
                call.respondText(bootstrap("token-${tokens.incrementAndGet()}"), ContentType.Application.Json)
            }
            get("/api/sessions") { call.respondText(sessions, ContentType.Application.Json) }
            webSocket("/ws") {
                send(KtorFrame.Text("""{"event":"ready","chat_id":"$chat","client_id":"companion"}"""))
                for (frame in incoming) {
                    if (frame !is KtorFrame.Text) continue
                    when ((Json.parseToJsonElement(frame.readText()).jsonObject)["type"]?.jsonPrimitive?.content) {
                        "attach" -> send(KtorFrame.Text("""{"event":"attached","chat_id":"$chat"}"""))
                        "message" -> {
                            send(KtorFrame.Text("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1","active_turn_id":"turn-1"}"""))
                            send(KtorFrame.Text("""{"event":"turn_model_updated","chat_id":"$chat","model_name":"deepseek","model_preset":"default","context_window_tokens":65536}"""))
                            send(KtorFrame.Text("""{"event":"delta","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1","text":"po"}"""))
                            send(KtorFrame.Text("""{"event":"delta","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1","text":"ng"}"""))
                            send(KtorFrame.Text("""{"event":"stream_end","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1"}"""))
                            send(KtorFrame.Text("""{"event":"turn_end","chat_id":"$chat","turn_id":"turn-1","outcome":"completed","usage":{"total_tokens":7}}"""))
                        }
                    }
                }
            }
        }

        val core = NanobotCore(GatewayProfile(url = "http://localhost", secret = "s3cr3t"), deps(), client())
        val handle = core.chat(null)

        val connected = core.connect()
        assertEquals("deepseek-v4.1-flash:cloud", assertIs<ConnectResult.Connected>(connected).modelName)
        assertEquals(ConnectionState.Open, core.connection.value)
        assertEquals(chat, handle.id.value, "the greeting minted the chat")

        val receipt = assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "ping")))
        assertEquals("companion-1000-0", receipt.turnId)

        val settled = handle.thread.first { it.turn("turn-1")?.status == TurnStatus.Complete }

        assertEquals("pong", settled.turn("turn-1")?.answer?.text, "the answer streamed through the facade into the thread")
        assertEquals(null, settled.activeTurnId, "turn_end settles the stop button")
        assertEquals(7, settled.usage?.totalTokens, "usage comes from turn_end (D15.4)")
        assertEquals(1, tokens.get(), "one connect, one bootstrap")

        // The drawer came from the API, using the bootstrap's api_token (D15.6).
        val loaded = core.sessions.first { it.loaded }
        assertEquals(listOf("Round trip"), loaded.rows.map { it.title })

        core.disconnect(forget = false)
        assertEquals(0, core.activeChildJobs())
    }

    @Test
    fun reconnectsWithAFreshTokenAfterTheSocketCloses() = testApplication {
        val tokens = AtomicInteger()
        val handshakes = mutableListOf<String>()
        install(ServerWebSockets)
        routing {
            get("/webui/bootstrap") {
                call.respondText(bootstrap("token-${tokens.incrementAndGet()}"), ContentType.Application.Json)
            }
            get("/api/sessions") { call.respondText("""{"sessions":[]}""", ContentType.Application.Json) }
            webSocket("/ws") {
                handshakes += call.request.queryParameters["token"].orEmpty()
                send(KtorFrame.Text("""{"event":"ready","chat_id":"$chat","client_id":"companion"}"""))
                // The gateway drops the socket: a phone that went to sleep looks like this.
                close()
            }
        }

        val core = NanobotCore(GatewayProfile(url = "http://localhost", secret = "s3cr3t"), deps(), client())

        assertIs<ConnectResult.Connected>(core.connect())
        core.connection.first { it is ConnectionState.Offline }

        assertIs<ConnectResult.Connected>(core.connect())
        core.disconnect(forget = false)

        assertEquals(listOf("token-1", "token-2"), handshakes, "a reconnect handshakes with a token minted for it, never the spent one")
        assertEquals(0, core.activeChildJobs(), "no pump survives the teardown")
    }
}
