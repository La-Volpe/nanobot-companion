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
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The composer's model chip has to name the preset as soon as the app can know it. The
 * greeting carries no model (`runtime.py` `_connection_loop`) and a compacted thread page stores
 * no `turn_model_updated`, so a chat that has not run a turn on this device knows nothing from
 * the socket. The gateway's own answers to `GET /api/settings` and `GET /api/sessions` fill the
 * gap, with the WebUI's precedence (`ThreadShell.tsx` `activeModelPreset`).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelChipTest {

    private val chat = "a"

    private val settings = """
        {"version":{"current":"0.4.1"},
         "agent":{"model":"deepseek-v4.1-flash:cloud","provider":"ollama","model_preset":"primary",
                  "context_window_tokens":32768},
         "model_presets":[
           {"name":"primary","model":"deepseek-v4.1-flash:cloud","provider":"ollama","active":true,
            "context_window_tokens":32768},
           {"name":"cheap","model":"cheap-model","provider":"ollama","active":false,
            "context_window_tokens":8192}]}
    """.trimIndent()

    private val sessions = """
        {"sessions":[
          {"key":"websocket:a","title":"One","preview":"hi","model_preset":null,
           "updated_at":"2026-11-14T03:38:10.475000Z"},
          {"key":"websocket:b","title":"Two","preview":"hi","model_preset":"cheap",
           "updated_at":"2026-11-14T03:37:10.475000Z"}]}
    """.trimIndent()

    private val reads = mutableListOf<String>()

    private fun engine(status: HttpStatusCode = HttpStatusCode.OK): MockEngine = MockEngine { request ->
        reads += request.url.encodedPath
        val body = when {
            request.url.encodedPath == "/api/settings" -> settings
            request.url.encodedPath == "/api/sessions" -> sessions
            request.url.encodedPath.endsWith("/webui-thread") -> """{"events":[]}"""
            else -> """{"sessions":[]}"""
        }
        if (status == HttpStatusCode.OK) {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respondError(status)
        }
    }

    private suspend fun TestScope.core(engine: MockEngine, socket: FakeSocket = FakeSocket()): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "api_token":"api","model_name":"deepseek-v4.1-flash:cloud",
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        val payload = assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
        val deps = CoreDeps(
            clock = FakeClock(startMs = 1_000),
            secureStore = InMemorySecureStore(),
            files = InMemoryFileStore(),
            appInfo = FakeAppInfo(),
            dispatchers = CoreDispatchers(
                compute = UnconfinedTestDispatcher(testScheduler),
                main = UnconfinedTestDispatcher(testScheduler),
            ),
        )
        val core = NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(payload) }, { socket }),
            http = HttpClient(engine),
        )
        socket.push("""{"event":"ready","chat_id":"websocket:$chat","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        advanceUntilIdle()
        return core
    }

    @Test
    fun openingAChatNamesThePresetTheGatewayIsOnBeforeAnyTurn() = runTest {
        val core = core(engine())
        val handle = core.chat(chat)

        handle.open()
        // The settings read is the socket's, started at connect, so the chip follows it
        // rather than the other way round: waiting on the flow is what a reader sees, and on
        // Kotlin/Native the mock engine's answer lands outside the virtual clock.
        core.settings.first { it.loaded }

        assertEquals("primary", handle.composer.value.modelPreset, "the chip names the preset, not the bare word")
        assertEquals("deepseek-v4.1-flash:cloud", handle.composer.value.modelName)
    }

    @Test
    fun aChatsOwnPresetBeatsTheGatewaysCurrentOne() = runTest {
        val core = core(engine())
        val handle = core.chat("b")

        handle.open()
        // The drawer's rows carry this chat's own preset, and they are a read of their own: the
        // chip follows the read that names it (the settings read is the socket's).
        core.sessions.first { it.loaded }

        assertEquals("cheap", handle.composer.value.modelPreset, "the session row is what this chat runs")
    }

    @Test
    fun theTurnThatRanStillWinsOverBothReads() = runTest {
        val socket = FakeSocket()
        val core = core(engine(), socket)
        val handle = core.chat(chat)
        handle.open()
        core.sessions.first { it.loaded }

        socket.push(
            """{"event":"turn_model_updated","chat_id":"$chat",
                "model_name":"another-model","model_preset":"another"}""",
        )
        advanceUntilIdle()

        assertEquals("another", handle.composer.value.modelPreset)
        assertEquals("another-model", handle.composer.value.modelName)
    }

    @Test
    fun pickingAPresetShowsBeforeTheGatewayAnswers() = runTest {
        val core = core(engine())
        val handle = core.chat(chat)
        handle.open()
        advanceUntilIdle()

        handle.setModelPreset("cheap")
        advanceUntilIdle()

        assertEquals("cheap", handle.composer.value.modelPreset, "the chip shows the pick at once")
    }

    @Test
    fun aBrandNewChatNamesThePresetWithoutAnyChatBeingOpened() = runTest {
        val core = core(engine())

        // The shell's fresh chat: no drawer row was picked, so no `open()` of a chat with an id
        // ever ran. The chip still has to name the gateway's preset, because the device's
        // screenshot said "Model" on exactly this screen.
        val handle = core.chat(null)
        // The read runs beside the pump, so the chip follows it rather than the other way round.
        core.settings.first { it.loaded }

        assertEquals("primary", handle.composer.value.modelPreset, "the chip names the preset, not the bare word")
        assertEquals("deepseek-v4.1-flash:cloud", handle.composer.value.modelName)
        assertEquals(1, reads.count { it == "/api/settings" }, "and the read is the socket's, once")
    }

    @Test
    fun oneSettingsReadServesEveryChatThatOpens() = runTest {
        val core = core(engine())

        core.chat(chat).open()
        advanceUntilIdle()
        core.chat("b").open()
        advanceUntilIdle()

        assertEquals(1, reads.count { it == "/api/settings" }, "a second chat does not read settings again")
    }

    @Test
    fun aRefusedSettingsReadLeavesTheChipEmptyRatherThanWrong() = runTest {
        val core = core(engine(status = HttpStatusCode.Unauthorized))
        val handle = core.chat(chat)

        handle.open()
        advanceUntilIdle()

        assertNull(handle.composer.value.modelPreset, "nothing was read, so the chip says nothing")
        assertNull(handle.composer.value.modelName)
    }
}
