package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.testing.awaitValue
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * "No sessions" and "the read did not answer" are two different facts, and the drawer has to
 * draw them differently: the first is a fact about the gateway, the second is the app's problem
 * and must not be dressed up as "No sessions yet. Start one from the chat." This file pins the
 * core's half of that distinction: before it, every outcome of `GET /api/sessions` became
 * `loaded = true` with whatever rows happened to be there, so a 500 read as an empty drawer.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionReadStateTest {

    private val page = """
        {"sessions":[
          {"key":"websocket:a","created_at":"2026-11-14T03:00:00","updated_at":"2026-11-14T03:01:00",
           "title":"Pong exchange","preview":"pong"}]}
    """.trimIndent()

    private suspend fun TestScope.core(engine: MockEngine): NanobotCore {
        val socket = FakeSocket()
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
                // Unconfined: `ready` starts the drawer read, and the mock engine's answer has to
                // land on this dispatcher or the pump would wait on a virtual clock forever.
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
        socket.push("""{"event":"ready","chat_id":"a","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect(), "the greeting is what opens the socket")
        // The greeting starts a drawer read beside the pump, on the engine's own thread, and
        // `runCurrent` cannot cover that. It has to be waited for here rather than left to the
        // clock: an answer that lands after a test has set its own state would overwrite the very
        // thing the test then asserts (T70).
        core.sessions.awaitValue(what = "the drawer read the greeting starts") { it.loaded }
        return core
    }

    /**
     * What the next read answers. A field rather than a queue because `ready` already starts one
     * drawer read of its own, and a test that counted calls would be counting that one.
     */
    private var answer: HttpStatusCode = HttpStatusCode.OK
    private var body: String = page

    private fun engine(): MockEngine = MockEngine {
        if (answer == HttpStatusCode.OK) {
            respond(body, answer, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respondError(answer)
        }
    }

    @Test
    fun aFailedSessionsReadIsNotAnEmptyDrawer() = runTest {
        // A gateway this app has never read from: nothing to keep, so the failure is all there is.
        body = """{"sessions":[]}"""
        val core = core(engine())
        answer = HttpStatusCode.InternalServerError

        core.refreshSessions()
        val state = core.sessions.value

        assertTrue(state.loaded, "the read finished, so the spinner is over")
        assertTrue(state.failed, "and it did not answer")
        assertFalse(state.isEmpty, "'no sessions on this gateway' would be a lie about a failed read")
        assertEquals(emptyList(), state.rows)
    }

    @Test
    fun aGatewayWithNoSessionsIsAnEmptyDrawer() = runTest {
        body = """{"sessions":[]}"""
        val core = core(engine())

        core.refreshSessions()

        assertFalse(core.sessions.value.failed)
        assertTrue(core.sessions.value.isEmpty, "this one really is 'no sessions yet'")
    }

    @Test
    fun aLaterGoodReadClearsTheFailure() = runTest {
        val core = core(engine())
        answer = HttpStatusCode.InternalServerError

        core.refreshSessions()
        assertTrue(core.sessions.value.failed)

        answer = HttpStatusCode.OK
        core.refreshSessions()
        assertFalse(core.sessions.value.failed, "a read that answered is the current truth")
        assertEquals(listOf("websocket:a"), core.sessions.value.rows.map { it.key })
    }

    @Test
    fun aFailedRefreshKeepsTheRowsItHad() = runTest {
        val core = core(engine())

        core.refreshSessions()
        answer = HttpStatusCode.InternalServerError
        core.refreshSessions()

        assertEquals(listOf("websocket:a"), core.sessions.value.rows.map { it.key }, "a stale drawer beats an empty one")
        assertTrue(core.sessions.value.failed, "the stale rows are still a failed read, not a current one")
        assertFalse(core.sessions.value.isEmpty)
    }
}
