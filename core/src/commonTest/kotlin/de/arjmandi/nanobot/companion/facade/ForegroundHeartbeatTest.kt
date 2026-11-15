package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.engine.HeartbeatPolicy
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The foreground half of the heartbeat, at the level a screen sees: the app comes back, the
 * socket is asked, and a socket that answers nothing becomes the failure the banner already
 * knows how to show. No second failure surface, no new copy.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForegroundHeartbeatTest {

    private val chat = "websocket:a"
    private val heartbeat = HeartbeatPolicy()

    private suspend fun TestScope.core(socket: FakeSocket): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "api_token":"api",
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
        val core = NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(payload) }, { socket }),
            http = null,
        )
        socket.push("""{"event":"ready","chat_id":"$chat","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
        return core
    }

    private fun answering(socket: FakeSocket) {
        socket.answer = { text ->
            if ("\"attach\"" in text) socket.push("""{"event":"attached","chat_id":"$chat"}""")
        }
    }

    private fun questions(socket: FakeSocket): Int = socket.sent.count { "\"attach\"" in it }

    @Test
    fun aSocketThatIsSilentAfterAReturnShowsTheBanner() = runTest {
        val socket = FakeSocket()
        val core = core(socket)

        core.setForeground(false)
        core.setForeground(true)
        runCurrent()

        assertEquals(1, questions(socket), "the return asked the socket instead of waiting for the tick")
        assertEquals(ConnectionState.Open, core.connection.value, "still inside the window")

        advanceTimeBy(heartbeat.windowMs + 1)
        runCurrent()

        val state = assertIs<ConnectionState.Failed>(
            core.connection.value,
            "a socket that answers nothing is the failure the banner already draws",
        )
        assertEquals(ConnectionFailure.Timeout, state.failure)
        assertEquals(true, state.retryable, "and the existing \"Try again\" is what fixes it")
        core.disconnect(forget = false)
    }

    @Test
    fun aSocketThatAnswersTheReturnStaysOpen() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        answering(socket)

        core.setForeground(false)
        core.setForeground(true)
        runCurrent()
        advanceTimeBy(heartbeat.intervalMs * 2 + heartbeat.windowMs + 1)
        runCurrent()

        assertEquals(ConnectionState.Open, core.connection.value, "an answered heartbeat changes nothing")
        // A live socket keeps a timer alive; the test ends the way the app does, or the
        // scheduler has an endless timer to drain.
        core.disconnect(forget = false)
    }

    @Test
    fun aBackgroundedCoreAsksNothing() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        answering(socket)

        core.setForeground(true)
        runCurrent()
        core.setForeground(false)
        socket.sent.clear()

        advanceTimeBy(heartbeat.intervalMs * 3)
        runCurrent()

        assertEquals(0, questions(socket), "a phone in a pocket keeps no timer to ask the gateway with")
        core.disconnect(forget = false)
    }

    @Test
    fun aLaunchStartsWatchingWithoutWaitingForABackground() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        answering(socket)

        core.setForeground(true)
        runCurrent()

        assertEquals(1, questions(socket), "the app is in front of the user from the first frame")
        core.disconnect(forget = false)
    }
}
