package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The usage ring (plan.md C6.7): the denominator is the context window from
 * `turn_model_updated`, the numerator is `turn_end.usage.context_tokens` (D15.4), and both
 * live on the composer because that is where the ring is drawn.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsageTest {

    private val chat = "websocket:a"

    private suspend fun TestScope.core(socket: FakeSocket, engine: MockEngine? = null): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "api_token":"api",
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        val payload = assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
        val deps = CoreDeps(
            clock = FakeClock(startMs = 1_000),
            secureStore = InMemorySecureStore(),
            files = InMemoryFileStore(),
            appInfo = FakeAppInfo(),
            dispatchers = CoreDispatchers(
                compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                main = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            ),
        )
        val core = NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(payload) }, { socket }),
            http = engine?.let(::HttpClient),
        )
        socket.push("""{"event":"ready","chat_id":"$chat","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
        socket.push("""{"event":"attached","chat_id":"$chat"}""")
        runCurrent()
        return core
    }

    @Test
    fun contextWindowFromTurnModelUpdated() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        socket.push(
            """{"event":"turn_model_updated","chat_id":"$chat","model_name":"m","model_preset":"p","context_window_tokens":200000}""",
        )
        advanceUntilIdle()

        assertEquals(200_000, handle.composer.value.contextWindowTokens)
    }

    @Test
    fun settledTurnFillsTheRing() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        socket.push(
            """{"event":"turn_model_updated","chat_id":"$chat","model_name":"m","model_preset":"p","context_window_tokens":200000}""",
        )
        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1"}""")
        socket.push(
            """{"event":"turn_end","chat_id":"$chat","turn_id":"turn-1","outcome":"completed",
                "usage":{"total_tokens":1234,"context_tokens":9000,"prompt_tokens":1000,"completion_tokens":234}}""".trimIndent(),
        )
        advanceUntilIdle()

        val composer = handle.composer.value
        assertEquals(9_000, composer.usage?.contextTokens, "the numerator is turn_end's context_tokens")
        assertEquals(200_000, composer.contextWindowTokens, "and the denominator survives the turn ending")
        assertEquals(1_234, composer.usage?.totalTokens)
    }

    @Test
    fun aTurnWithoutUsageKeepsTheLastRing() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1"}""")
        socket.push("""{"event":"turn_end","chat_id":"$chat","turn_id":"turn-1","usage":{"context_tokens":500}}""")
        advanceUntilIdle()
        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-2"}""")
        socket.push("""{"event":"turn_end","chat_id":"$chat","turn_id":"turn-2"}""")
        advanceUntilIdle()

        assertEquals(500, handle.composer.value.usage?.contextTokens, "a usage-less turn does not blank the ring")
    }

    /**
     * The owner's own gateway (`/api/settings`, 2026-11-15): `default` is the built-in preset at
     * 200 000, `primary` is the active one at 1 000 000, `Vision and OCR` at 256 000, and
     * `agent.context_window_tokens` is 1 000 000.
     */
    private val ownerSettings = """
        {"agent":{"model":"glm-5.3-flash:cloud","provider":"ollama","model_preset":"primary",
                  "context_window_tokens":1000000},
         "model_presets":[
           {"name":"default","model":"anthropic/claude-opus-4-5","provider":"auto",
            "context_window_tokens":200000,"active":false,"is_default":true},
           {"name":"primary","model":"glm-5.3-flash:cloud","provider":"ollama",
            "context_window_tokens":1000000,"active":true,"is_default":false},
           {"name":"Vision and OCR","model":"gemma4:31b-cloud","provider":"ollama",
            "context_window_tokens":256000,"active":false,"is_default":false}]}
    """.trimIndent()

    private fun settingsEngine(failFirstRead: Boolean = false): MockEngine {
        var reads = 0
        return MockEngine { request ->
            if (request.url.encodedPath != "/api/settings") {
                respond("""{"sessions":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                reads += 1
                if (failFirstRead && reads == 1) {
                    respondError(HttpStatusCode.InternalServerError)
                } else {
                    respond(ownerSettings, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
    }

    /**
     * T37: a stored turn carries its usage and, when the page was compacted or written by an
     * older gateway, no window. The WebUI then falls back — usage snapshot, then the thread,
     * then the preset — and the ring keeps a denominator instead of going blank. The pinned
     * case: 141 510 of 1 000 000 is **14%**, not the 71% a 200 000 window would print.
     */
    @Test
    fun aPresetWindowIsTheDenominatorWhenNoFrameCarriedOne() = runTest {
        val socket = FakeSocket()
        val core = core(socket, settingsEngine())
        core.refreshSettings()
        advanceUntilIdle()
        val handle = core.chat(chat)

        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1"}""")
        socket.push(
            """{"event":"turn_end","chat_id":"$chat","turn_id":"turn-1","outcome":"completed",
                "usage":{"total_tokens":141510,"context_tokens":141510}}""".trimIndent(),
        )
        advanceUntilIdle()

        val composer = handle.composer.value
        assertEquals(141_510, composer.usage?.contextTokens)
        assertEquals(1_000_000, composer.contextWindowTokens, "the gateway's own window, not a blank ring")
        assertEquals(14, composer.contextPercent, "Math.min(100, Math.round(141510 / 1000000 * 100))")
    }

    /**
     * The settings read lands after the frames that used it: a chat whose turn came with usage
     * but no window gets its denominator when `/api/settings` answers, not only on the next turn.
     */
    @Test
    fun aLateSettingsReadFillsTheRing() = runTest {
        val socket = FakeSocket()
        // The read at connect fails, as it can; the retry from Settings is what answers.
        val core = core(socket, settingsEngine(failFirstRead = true))
        val handle = core.chat(chat)

        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1"}""")
        socket.push("""{"event":"turn_end","chat_id":"$chat","turn_id":"turn-1","usage":{"context_tokens":141510}}""")
        advanceUntilIdle()
        assertEquals(null, handle.composer.value.contextPercent, "nothing has said what the window is yet")

        core.refreshSettings()
        advanceUntilIdle()

        assertEquals(1_000_000, handle.composer.value.contextWindowTokens)
        assertEquals(14, handle.composer.value.contextPercent, "the late read fills the ring it can fill")
    }

    /** A window a turn did carry is the chat's own model, and nothing may overwrite it (T37). */
    @Test
    fun theWindowATurnCarriedOutranksTheGatewaySetting() = runTest {
        val socket = FakeSocket()
        val core = core(socket, settingsEngine())
        core.refreshSettings()
        advanceUntilIdle()
        val handle = core.chat(chat)

        socket.push(
            """{"event":"turn_model_updated","chat_id":"$chat","model_name":"anthropic/claude-opus-4-5",
                "model_preset":"default","context_window_tokens":200000}""".trimIndent(),
        )
        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1"}""")
        socket.push(
            """{"event":"turn_end","chat_id":"$chat","turn_id":"turn-1",
                "context_window_tokens":200000,
                "usage":{"total_tokens":141510,"context_tokens":141510}}""".trimIndent(),
        )
        advanceUntilIdle()

        val composer = handle.composer.value
        assertEquals(200_000, composer.contextWindowTokens, "this chat runs the 200k preset, whatever Settings says")
        assertEquals(71, composer.contextPercent, "141 510 of 200 000 is 71%, rounded the way the WebUI rounds")

    }
}
