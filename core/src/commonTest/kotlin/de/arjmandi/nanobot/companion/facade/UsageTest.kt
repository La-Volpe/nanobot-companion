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

    private suspend fun TestScope.core(socket: FakeSocket): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
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
            http = null,
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
}
