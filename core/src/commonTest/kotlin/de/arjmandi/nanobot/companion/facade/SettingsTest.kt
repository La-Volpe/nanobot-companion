package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.CoreError
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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Settings → Overview (plan.md C7.1): what the gateway is, which model answers, and what it
 * has spent. The design's one hard rule here is a missing field, not a present one — the
 * gateway's settings payload has **no uptime**, so Overview shows version and server instead
 * of inventing a third line (design README, "Honest constraints").
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsTest {

    private val settings = """
        {"version":{"current":"0.4.1","commit":"28d73b6942621ffedc0d7a9639e429595f34bd36"},
         "environment":{"python_version":"3.12.9","os":"Darwin","os_version":"27.2.0","architecture":"arm64"},
         "agent":{"model":"deepseek-v4.1-flash:cloud","provider":"ollama","model_preset":"primary",
                  "context_window_tokens":32768,"temperature":0.1,"max_tokens":4096},
         "surface":"browser","runtime_surface":"browser",
         "transcription":{"enabled":true,"provider":"groq","model":"whisper-large-v3","max_duration_sec":120},
         "web":{"enable":true,"search":{"max_results":5,"timeout":30}},
         "web_search":{"enable":true}}
    """.trimIndent()

    private val usage = """
        {"total_tokens":498711,"total_tokens_30d":498711,"total_tokens_365d":498711,
         "requests_30d":139,"failed_requests_30d":25,"active_days_30d":1,
         "current_streak_days":1,"longest_streak_days":1,"peak_day_tokens":498711,
         "cache_read_tokens_30d":433792,"cache_read_rate_30d":0.88,
         "updated_at":"2026-11-14T03:38:10.475000Z",
         "days":[{"date":"2026-11-14","input_tokens":488528,"output_tokens":10183,"total_tokens":498711,
                  "requests":139,"successful_requests":114,"failed_requests":25,"duration_ms":129684,
                  "sources":{
                    "user":{"input_tokens":303236,"output_tokens":2093,"total_tokens":305329,"requests":58,"failed_requests":6},
                    "dream":{"input_tokens":181533,"output_tokens":5730,"total_tokens":187263,"requests":43,"failed_requests":13},
                    "system":{"input_tokens":3759,"output_tokens":2360,"total_tokens":6119,"requests":38,"failed_requests":6}}}]}
    """.trimIndent()

    private fun engine(
        onSettings: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to settings },
        onUsage: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to usage },
    ): MockEngine = MockEngine { request ->
        val (status, body) = when (request.url.encodedPath) {
            "/api/settings" -> onSettings()
            "/api/settings/usage" -> onUsage()
            else -> HttpStatusCode.OK to """{"sessions":[]}"""
        }
        if (status == HttpStatusCode.OK) {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respondError(status)
        }
    }

    private suspend fun TestScope.core(engine: MockEngine): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "api_token":"api","model_name":"deepseek-v4.1-flash:cloud",
             "terminal":{"webui":{"version":"0.4.1","min_protocol":1,"max_protocol":1,
               "capabilities":["webui.core.v1"]}}}
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
        val socket = FakeSocket()
        val core = NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(payload) }, { socket }),
            http = HttpClient(engine),
        )
        socket.push("""{"event":"ready","chat_id":"websocket:a","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
        return core
    }

    @Test
    fun parsesVersionAndEnvironment() = runTest {
        val core = core(engine())

        assertNull(core.refreshSettings())

        val state = core.settings.value
        assertEquals("0.4.1", state.version?.current)
        assertEquals("28d73b6942621ffedc0d7a9639e429595f34bd36", state.version?.commit)
        assertEquals("Darwin", state.environment?.os)
        assertEquals("3.12.9", state.environment?.pythonVersion)
        assertEquals("arm64", state.environment?.architecture)
        assertEquals("deepseek-v4.1-flash:cloud", state.modelName)
        assertEquals(32_768, state.contextWindowTokens)
        assertEquals("primary", state.modelPreset)
    }

    @Test
    fun noUptimeFieldIsNotAnError() = runTest {
        // The design's Overview shows version and server *because* the payload carries no
        // uptime. SettingsState has no field for one, so a gateway that grows one must not
        // break the parse — and a gateway without one is the normal case, not a gap.
        val core = core(engine(onSettings = { HttpStatusCode.OK to settings }))
        assertNull(core.refreshSettings())
        assertTrue(core.settings.value.version?.current == "0.4.1")

        val withAnExtraField = settings.dropLast(1) + ""","uptime":{"current_s":8123}}"""
        val second = core(engine(onSettings = { HttpStatusCode.OK to withAnExtraField }))
        assertNull(second.refreshSettings())
        assertEquals("28d73b6942621ffedc0d7a9639e429595f34bd36", second.settings.value.version?.commit)
    }

    @Test
    fun usageTotalsMap() = runTest {
        val core = core(engine())
        core.refreshSettings()

        val totals = core.settings.value.usage ?: error("usage was not parsed")
        assertEquals(498_711L, totals.totalTokens)
        assertEquals(498_711L, totals.totalTokens30d)
        assertEquals(139, totals.requests30d)
        assertEquals(25, totals.failedRequests30d)

        val day = totals.days.single()
        assertEquals("2026-11-14", day.date)
        assertEquals(498_711L, day.totalTokens)
        assertEquals(listOf("dream", "system", "user"), day.sources.keys.sorted())
        assertEquals(305_329L, day.sources.getValue("user").totalTokens)
        assertEquals(58, day.sources.getValue("user").requests)
    }

    @Test
    fun anUnknownSourceIsKeptRatherThanDropped() = runTest {
        val withNewSource = usage.replace(
            "\"sources\":{",
            "\"sources\":{\"mcp\":{\"input_tokens\":1,\"output_tokens\":2,\"total_tokens\":3,\"requests\":1,\"failed_requests\":0},",
        )
        val core = core(engine(onUsage = { HttpStatusCode.OK to withNewSource }))
        core.refreshSettings()

        val sources = core.settings.value.usage!!.days.single().sources
        assertEquals(3L, sources.getValue("mcp").totalTokens, "a source the app has never seen still counts")
    }

    @Test
    fun aRefusedReadIsTheTaxonomyNotAnException() = runTest {
        val core = core(engine(onSettings = { HttpStatusCode.Unauthorized to "" }))

        val error = core.refreshSettings()

        assertIs<CoreError.AuthFailed>(error)
        assertNull(core.settings.value.version, "a refused read leaves the screen empty rather than wrong")
    }

    @Test
    fun transcriptionBoundsComeFromTheGateway() = runTest {
        val core = core(engine())
        core.refreshSettings()

        assertEquals("whisper-large-v3", core.settings.value.transcription?.model)
        assertEquals(120, core.settings.value.transcription?.maxDurationSeconds)
        assertTrue(core.settings.value.transcription?.enabled == true)
    }
}
