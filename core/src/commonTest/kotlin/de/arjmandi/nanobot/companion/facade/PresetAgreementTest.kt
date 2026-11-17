package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.testing.ReadLog
import de.arjmandi.nanobot.companion.testing.awaitValue
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * One preset, one answer. The owner opened a new chat on the round-3 build and the composer
 * read `GLM` over a `256K` ring: `GLM` is what `primary` runs and 256 000 is `Vision and OCR`'s
 * window, so one row was describing two presets, one of them belonging to a chat the owner was
 * not in.
 *
 * Measured against the dev gateway (2026-11-16, `core-cli send --prompt "/model broken"`):
 * `GET /api/settings` still answers `agent.model_preset: primary` afterwards, because `/model`
 * writes the preset into the *session's* metadata (`loop.py:587 set_session_model_preset`) and
 * publishes no frame at all, while `GET /api/sessions` answers the chat's own
 * `model_preset: broken` on its row. So the settings read cannot settle a switch, the chat's row
 * can, and neither may supply half a row: whatever names the preset names its model and its
 * window too.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PresetAgreementTest {

    private val chat = "a"
    private val other = "b"

    /** The owner's own shape: a configured `primary` and a `Vision and OCR` at a quarter of it. */
    private val settings = """
        {"version":{"current":"0.4.1"},
         "agent":{"model":"GLM-4.6","provider":"ollama","model_preset":"primary",
                  "context_window_tokens":1000000},
         "model_presets":[
           {"name":"primary","model":"GLM-4.6","provider":"ollama","active":true,
            "context_window_tokens":1000000},
           {"name":"Vision and OCR","model":"qwen3-vl","provider":"ollama","active":false,
            "context_window_tokens":256000}]}
    """.trimIndent()

    /** What `GET /api/sessions` answers; a test rewrites it to say what the gateway applied. */
    private var sessions: String = """{"sessions":[]}"""

    private val reads = ReadLog()

    private fun engine(): MockEngine = MockEngine { request ->
        reads.record(request)
        val body = when {
            request.url.encodedPath == "/api/settings" -> settings
            request.url.encodedPath == "/api/settings/usage" -> """{"days":[]}"""
            request.url.encodedPath == "/api/sessions" -> sessions
            request.url.encodedPath.endsWith("/webui-thread") -> """{"events":[]}"""
            else -> """{"sessions":[]}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    /** A row for one chat as the gateway stores it: keyed `websocket:<uuid>`, preset or nothing. */
    private fun row(chatId: String, preset: String?): String {
        val value = preset?.let { "\"$it\"" } ?: "null"
        return """
            {"sessions":[{"key":"websocket:$chatId","title":"One","preview":"hi",
              "model_preset":$value,"updated_at":"2026-11-16T09:38:10.475000Z"}]}
        """.trimIndent()
    }

    /**
     * The drawer read is an HTTP round trip beside the pump, so a test that is about what the rows
     * say waits for the answer rather than for the clock: `advanceUntilIdle` does not cover a read
     * that is still on the wire (`ModelChipTest` learned the same).
     */
    private suspend fun readsOf(core: NanobotCore, chatId: String, preset: String?) {
        core.sessions.awaitValue(what = "the drawer row for $chatId with preset $preset") { state ->
            state.rows.any { row -> row.chatId == chatId && row.modelPreset == preset }
        }
    }

    /** The pick's own half of the row, asserted before a read that only the settled turn starts. */
    private fun assertThePickShowsItsOwnPreset(handle: ChatHandle) {
        assertEquals("qwen3-vl", handle.composer.value.modelName, "the pick names its own model while it is in flight")
        assertEquals(256_000, handle.composer.value.contextWindowTokens, "and its own window")
    }

    /** Each connect takes the next socket, because a bootstrap token is single-use. */
    private fun TestScope.core(vararg sockets: FakeSocket): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "api_token":"api","model_name":"GLM-4.6",
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
        val next = sockets.toMutableList()
        return NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(payload) }, { next.removeAt(0) }),
            http = HttpClient(engine()),
        )
    }

    private suspend fun TestScope.connect(core: NanobotCore, socket: FakeSocket, chatId: String) {
        socket.push("""{"event":"ready","chat_id":"$chatId","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
    }

    private fun FakeSocket.turnEnds(chatId: String, contextTokens: Int) {
        push(
            """{"event":"turn_end","chat_id":"$chatId","turn_id":"t46","outcome":"completed",
                "usage":{"context_tokens":$contextTokens,"total_tokens":$contextTokens}}""".trimIndent(),
        )
    }

    /**
     * The reported row, in one test: the pick's own model and the pick's own window, and the ring
     * dividing by that same window. 64 000 of 256 000 is a quarter; against `primary`'s million it
     * would be 6%, which is the wrong shape of the same mistake.
     */
    @Test
    fun aPresetNamesItsOwnModelItsOwnWindowAndTheRingsDenominator() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connect(core, socket, chat)
        val handle = core.chat(chat)
        core.settings.awaitValue(what = "the gateway's settings") { it.loaded }

        handle.setModelPreset("Vision and OCR")
        runCurrent()
        // The settled turn asks for the drawer's rows again, because a pick waits on that answer.
        // A row for another chat is what proves the read landed, and a chat with no row keeps its
        // pick; the ring is what the test reads, so the ring is what it waits for.
        sessions = row(other, null)
        socket.turnEnds(chat, contextTokens = 64_000)
        readsOf(core, other, null)
        handle.composer.awaitValue(what = "the ring on the picked preset's window") { it.contextPercent == 25 }

        val composer = handle.composer.value
        assertEquals("Vision and OCR", composer.modelPreset, "the chip names the preset that was picked")
        assertEquals("qwen3-vl", composer.modelName, "and the model that preset runs, not the configured GLM")
        assertEquals(256_000, composer.contextWindowTokens, "and the window that preset has")
        assertEquals(25, composer.contextPercent, "so the ring divides by the same preset's window")
    }

    /** A pick is a fact about one chat on one socket; the socket that replaces it knows nothing. */
    @Test
    fun aPickDoesNotSurviveTheSocketItWasMadeOn() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val core = core(first, second)
        connect(core, first, chat)
        val handle = core.chat(chat)
        core.settings.awaitValue(what = "the gateway's settings") { it.loaded }

        handle.setModelPreset("Vision and OCR")
        runCurrent()
        assertEquals("Vision and OCR", handle.composer.value.modelPreset, "the pick shows while it is in flight")

        // The phone slept and came back: a fresh token, a fresh socket, and a chat the gateway's
        // rows say nothing about. `/model` never answered with a frame, so nothing cleared it.
        sessions = row(other, null)
        connect(core, second, other)
        readsOf(core, other, null)
        // The greeting clears the pick and the drawer read is what republishes the chip, so the row
        // landing is not yet the chip changing: wait for the chip itself. The whole row, because
        // `modelName` was the one field this test asserted without ever waiting for it, and the
        // hosted runner failed it on a string comparison (run 44042007234).
        handle.composer.awaitValue(what = "the chip back on the gateway's preset") {
            it.modelPreset == "primary" && it.modelName == "GLM-4.6" && it.contextWindowTokens == 1_000_000
        }

        val composer = handle.composer.value
        assertEquals("primary", composer.modelPreset, "the chat is on what the gateway configured")
        assertEquals("GLM-4.6", composer.modelName)
        assertEquals(1_000_000, composer.contextWindowTokens, "and the row agrees with itself")
    }

    /**
     * The chat the gateway's own row puts on another preset, reopened the next morning: no turn has
     * run on this socket, so no frame names a model, and the row is the only evidence there is. The
     * chip must name that preset's model and that preset's window — not the configured `GLM` over a
     * million (T47).
     */
    @Test
    fun aChatOnAnotherPresetNamesThatPresetsModelAndWindow() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connect(core, socket, chat)
        val handle = core.chat(chat)
        core.settings.awaitValue(what = "the gateway's settings") { it.loaded }

        sessions = row(chat, "Vision and OCR")
        core.refreshSessions()
        readsOf(core, chat, "Vision and OCR")
        handle.composer.awaitValue(what = "the chip on the chat's own preset") {
            it.modelPreset == "Vision and OCR"
        }

        val composer = handle.composer.value
        assertEquals("Vision and OCR", composer.modelPreset)
        assertEquals("qwen3-vl", composer.modelName, "the model that preset runs, not the configured GLM")
        assertEquals(256_000, composer.contextWindowTokens, "and the window that preset has")
        assertEquals(null, composer.contextPercent, "with no usage yet the ring has nothing to divide")
    }

    /**
     * The pick the gateway applied: its session row is the confirmation, and it says the same
     * preset the chip does, so the sheet's tick, the chip's name and the ring's window all
     * describe one model.
     */
    @Test
    fun aSettledSwitchLeavesTheChipTheRingAndTheTickAgreeing() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connect(core, socket, chat)
        val handle = core.chat(chat)
        core.settings.awaitValue(what = "the gateway's settings") { it.loaded }
        handle.setModelPreset("Vision and OCR")
        runCurrent()
        assertThePickShowsItsOwnPreset(handle)

        // `GET /api/sessions` after the switch: `set_session_model_preset` wrote it (loop.py:587).
        sessions = row(chat, "Vision and OCR")
        socket.turnEnds(chat, contextTokens = 64_000)
        readsOf(core, chat, "Vision and OCR")
        handle.composer.awaitValue(what = "the settled composer row") { it.contextPercent == 25 }

        val composer = handle.composer.value
        assertEquals("Vision and OCR", composer.modelPreset)
        assertEquals("qwen3-vl", composer.modelName)
        assertEquals(256_000, composer.contextWindowTokens)
        assertEquals(25, composer.contextPercent)
        val offered = ModelPresets.offered(core.settings.value, composer.modelPreset)
        assertEquals(
            listOf("Vision and OCR"),
            offered.filter { it.active }.map { it.name },
            "the sheet ticks the preset the row just confirmed",
        )
        // Not "exactly two": the count was never the claim, and a debounced drawer refresh can add a
        // third legitimately. What has to be true is that the switch's own read went out as well as
        // the greeting's — the settled row above is the proof that at least those two answered.
        val paths = reads.paths()
        assertTrue(
            paths.count { path -> path == "/api/sessions" } >= 2,
            "the greeting's read and the one the settled switch asked for both went out: $paths",
        )
    }

    /**
     * The pick the gateway refused: an unknown or removed preset answers with a message and a
     * `turn_end`, and writes no metadata. Everything goes back together, because a chip that keeps
     * the preset of a switch that did not happen is the same lie as the name from another preset.
     */
    @Test
    fun aRefusedSwitchRevertsTheNameTheWindowAndTheRing() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connect(core, socket, chat)
        val handle = core.chat(chat)
        core.settings.awaitValue(what = "the gateway's settings") { it.loaded }
        handle.setModelPreset("Vision and OCR")
        runCurrent()
        assertThePickShowsItsOwnPreset(handle)

        // The gateway's refusal leaves the chat's metadata as it was: no preset stored.
        sessions = row(chat, null)
        socket.push(
            """{"event":"message","chat_id":"$chat","turn_id":"t46",
                "text":"Could not switch model preset: unknown preset"}""".trimIndent(),
        )
        socket.turnEnds(chat, contextTokens = 64_000)
        readsOf(core, chat, null)
        // The revert is `settlePicks` running after the rows land, so the row arriving is not the
        // revert: the chip is what the test reads, so the chip is what it waits for.
        handle.composer.awaitValue(what = "the chip back on the chat's own preset") {
            it.modelPreset == "primary" && it.contextPercent == 6
        }

        val composer = handle.composer.value
        assertEquals("primary", composer.modelPreset, "the chip goes back to what the chat is really on")
        assertEquals("GLM-4.6", composer.modelName, "and not just the chip: the name too")
        assertEquals(1_000_000, composer.contextWindowTokens, "and the window")
        assertEquals(6, composer.contextPercent, "and the ring that divides by it")
    }

    /**
     * The other half of the rule, so the confirmation is not vacuous: a chat the drawer has no row
     * for yet proves nothing either way, and a pick with nothing against it stands.
     */
    @Test
    fun aChatWithNoRowYetKeepsItsPick() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connect(core, socket, chat)
        val handle = core.chat(chat)
        core.settings.awaitValue(what = "the gateway's settings") { it.loaded }
        handle.setModelPreset("Vision and OCR")
        runCurrent()
        assertThePickShowsItsOwnPreset(handle)

        // A row for another chat, so the read has landed while this one still has none.
        sessions = row(other, null)
        socket.turnEnds(chat, contextTokens = 64_000)
        readsOf(core, other, null)
        handle.composer.awaitValue(what = "the ring after the settled turn") { it.contextPercent == 25 }

        assertEquals("qwen3-vl", handle.composer.value.modelName, "no row came back to contradict it")
        assertEquals(256_000, handle.composer.value.contextWindowTokens)
    }
}
