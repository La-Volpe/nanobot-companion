package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.projection.PaletteGroupKind
import de.arjmandi.nanobot.companion.projection.PaletteQuery
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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The palettes are read when the sheet opens, not at connect: the drawer's sessions
 * come from the same `GET /api/sessions` the list uses, and the commands and installed apps
 * are two bearer reads of their own. A failed read leaves the previous catalog alone so the
 * sheet can offer a retry instead of an empty list.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PaletteRefreshTest {

    private val sessions = """
        {"sessions":[
          {"key":"websocket:a","created_at":"2026-11-14T03:00:00","updated_at":"2026-11-14T03:01:00",
           "title":"Exact word pong exchange","preview":"pong","handle":{"id":"handle_1","name":"fomo"}},
          {"key":"websocket:b","created_at":"2026-11-14T02:00:00","updated_at":"2026-11-14T02:01:00",
           "title":"","preview":"drawer notes","handle":{"id":"handle_2","name":"jana"}}]}
    """.trimIndent()

    private val commands = """
        {"commands":[
          {"command":"/model","title":"Switch model preset","description":"Show or switch.",
           "icon":"brain","arg_hint":"[preset]","lifecycle":"side_channel","accepts_args":true},
          {"command":"/stop","title":"Stop current task","description":"Cancel the turn.",
           "icon":"square","arg_hint":"","lifecycle":"stop_active_turn","accepts_args":false}]}
    """.trimIndent()

    private val apps = """
        {"apps":[{"name":"ffmpeg","display_name":"FFmpeg","category":"media",
          "description":"Transcode video","entry_point":"ffmpeg","installed":true}],
         "installed_count":1,"catalog_updated_at":null}
    """.trimIndent()

    private suspend fun TestScope.core(engine: MockEngine, socket: FakeSocket): NanobotCore {
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
                // Unconfined: `ready` triggers the drawer read, and the mock engine's reply has
                // to land on this dispatcher or the pump waits forever on a virtual clock.
                compute = UnconfinedTestDispatcher(testScheduler),
                main = UnconfinedTestDispatcher(testScheduler),
            ),
        )
        val core = NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ de.arjmandi.nanobot.companion.transport.BootstrapOutcome.Ready(payload) }, { socket }),
            http = HttpClient(engine),
        )
        socket.push("""{"event":"ready","chat_id":"websocket:a","client_id":"companion"}""")
        val connected = core.connect()
        assertIs<ConnectResult.Connected>(connected, "connect: $connected")
        runCurrent()
        return core
    }

    private fun engine(
        onCommands: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to commands },
        onApps: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to apps },
    ): MockEngine = MockEngine { request ->
        val path = request.url.encodedPath
        val (status, body) = when (path) {
            "/api/commands" -> onCommands()
            "/api/settings/cli-apps" -> onApps()
            else -> HttpStatusCode.OK to sessions
        }
        if (status == HttpStatusCode.OK) {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respondError(status)
        }
    }

    @Test
    fun refreshReadsCommandsAndInstalledApps() = runTest {
        val socket = FakeSocket()
        val core = core(engine(), socket)

        assertNull(core.refreshPalettes())

        val catalog = core.palette.value
        assertEquals(listOf("/model", "/stop"), catalog.commands.map { it.command })
        assertEquals(listOf("ffmpeg"), catalog.apps.map { it.name })
    }

    @Test
    fun sessionsBecomesAMentionGroupAfterTheDrawerRefreshes() = runTest {
        val socket = FakeSocket()
        val core = core(engine(), socket)

        core.refreshSessions()
        core.refreshPalettes()

        val state = PaletteQuery.forComposer(core.palette.value, "@")
        assertEquals(
            listOf(PaletteGroupKind.Sessions, PaletteGroupKind.CliApps),
            state?.groups?.map { it.kind },
        )
        assertEquals(listOf("@fomo", "@jana"), state?.groups?.first()?.items?.map { it.label })
        assertEquals("@ffmpeg", state?.groups?.last()?.items?.first()?.label)
    }

    @Test
    fun aFailedCommandsReadKeepsTheCatalogItHad() = runTest {
        val socket = FakeSocket()
        var failing = false
        val core = core(engine(onCommands = {
            if (failing) HttpStatusCode.InternalServerError to "" else HttpStatusCode.OK to commands
        }), socket)

        assertNull(core.refreshPalettes())
        failing = true
        val error = core.refreshPalettes()

        assertIs<de.arjmandi.nanobot.companion.transport.CoreError.ServerError>(error)
        assertEquals(2, core.palette.value.commands.size, "a failed refresh does not empty the sheet")
    }

    @Test
    fun paletteReadsUseTheBootstrapApiToken() = runTest {
        val socket = FakeSocket()
        val seen = mutableMapOf<String, String?>()
        val engine = MockEngine { request ->
            seen[request.url.encodedPath] = request.headers[HttpHeaders.Authorization]
            val body = when (request.url.encodedPath) {
                "/api/commands" -> commands
                "/api/settings/cli-apps" -> apps
                else -> sessions
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val core = core(engine, socket)

        core.refreshPalettes()

        assertEquals("Bearer api", seen["/api/commands"])
        assertEquals("Bearer api", seen["/api/settings/cli-apps"])
        assertTrue(
            engine.requestHistory.last().url.parameters["installed_only"] == "1",
            "only installed apps are mentionable",
        )
    }
}
