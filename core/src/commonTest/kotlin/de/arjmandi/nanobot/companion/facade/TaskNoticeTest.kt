package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.projection.TaskOutcome
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The facade's half of the notice rule: the frames the gateway actually sends
 * (`turn_end`, `recovery_state`) become one `taskNotice` the platform posts, and the screen
 * the user is on suppresses it. This is the bug the owner reported: the frames arrived, and
 * nothing turned them into a banner.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskNoticeTest {

    private val chat = "websocket:a"

    private suspend fun TestScope.core(socket: FakeSocket): NanobotCore {
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

    private fun settled(socket: FakeSocket, turnId: String = "turn-1", outcome: String? = null) {
        val fields = if (outcome == null) "" else ", \"outcome\": \"" + outcome + "\""
        socket.push("""{"event":"turn_end","chat_id":"$chat","turn_id":"$turnId"$fields}""")
    }

    @Test
    fun aTurnThatSettlesWhileTheAppIsAwayRaisesANotice() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        core.setForeground(false)

        settled(socket)
        runCurrent()

        val notice = core.taskNotice.value
        assertEquals(chat, notice?.chatId)
        assertEquals(TaskOutcome.Completed, notice?.outcome)
    }

    @Test
    fun theChatOnScreenRaisesNothing() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        core.setForeground(true)
        core.markOnScreen(handle)

        settled(socket)
        runCurrent()

        assertNull(core.taskNotice.value)
    }

    @Test
    fun aBackgroundedAppStillRaisesTheChatItLeftOnScreen() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        core.markOnScreen(handle)
        core.setForeground(true)
        // Back to the home screen: the thread the app left behind is no longer being looked at.
        core.setForeground(false)

        settled(socket)
        runCurrent()

        assertEquals(chat, core.taskNotice.value?.chatId)
    }

    @Test
    fun aCancelledTurnRaisesNothing() = runTest {
        val socket = FakeSocket()
        val core = core(socket)

        settled(socket, outcome = "cancelled")
        runCurrent()

        assertNull(core.taskNotice.value)
    }

    @Test
    fun aFailedTurnRaisesAFailedNotice() = runTest {
        val socket = FakeSocket()
        val core = core(socket)

        settled(socket, outcome = "failed")
        runCurrent()

        assertEquals(TaskOutcome.Failed, core.taskNotice.value?.outcome)
    }

    @Test
    fun aRecoveryWaitingForTheUserRaisesANotice() = runTest {
        val socket = FakeSocket()
        val core = core(socket)

        socket.push(
            """{"event":"recovery_state","chat_id":"$chat","recovery_id":"rec-1",
                "status":"awaiting_user","reason":"tool_state_uncertain","can_continue":true}""",
        )
        runCurrent()

        assertEquals(TaskOutcome.NeedsAttention, core.taskNotice.value?.outcome)
    }

    @Test
    fun oneSettledTurnIsAnnouncedOnce() = runTest {
        val socket = FakeSocket()
        val core = core(socket)

        settled(socket)
        runCurrent()
        val first = core.taskNotice.value

        // A second copy of the same settle frame, which a replayed batch can produce, must not
        // become a second banner. The notice the platform holds does not move.
        settled(socket)
        runCurrent()

        assertEquals(first, core.taskNotice.value)
    }
}
