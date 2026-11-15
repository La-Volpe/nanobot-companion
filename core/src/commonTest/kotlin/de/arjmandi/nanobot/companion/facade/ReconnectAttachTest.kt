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
import de.arjmandi.nanobot.companion.transport.BootstrapSource
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapPayload
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A reconnect is a new socket, and a socket knows nothing of what the previous one was told: the
 * gateway subscribes per connection (`runtime.py` `_attach`, `_conn_chats`), so the chat on screen
 * has to be attached again. Found by the phone's own path — the owner backgrounds the app, comes
 * back, and the answer that was streaming never arrives because `open()` believed the dead
 * socket's `attached` still stood (C3.4, C5.4).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReconnectAttachTest {

    private val chat = "websocket:a"

    private val first = FakeSocket()
    private val second = FakeSocket()

    /** The tokens the two bootstraps minted, in order; the handshake has to carry each one. */
    private val tokens = mutableListOf("tok-1", "tok-2")
    private val handshakes = mutableListOf<String>()

    /** Each connect takes the next socket: one attempt, one socket, like the real thing. */
    private suspend fun TestScope.core(): NanobotCore {
        val minted = tokens.toMutableList()
        val sockets = mutableListOf(first, second)
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
        val bootstrap = BootstrapSource { secret ->
            // The gateway mints a single-use token per bootstrap, so a retry is a new one or
            // nothing at all (`transport/BootstrapClient.kt`).
            assertEquals("s3cr3t", secret)
            BootstrapOutcome.Ready(payload(minted.removeAt(0)))
        }
        val openSocket: (String) -> FakeSocket = { url ->
            handshakes += url
            sockets.removeAt(0)
        }
        return NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt(bootstrap, openSocket),
            http = null,
        )
    }

    private fun payload(token: String): BootstrapPayload {
        val text = """
            {"token":"$token","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        return assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
    }

    /** One connection: the greeting the gateway sends first, then the handshake the core waits for. */
    private suspend fun TestScope.connect(core: NanobotCore, socket: FakeSocket) {
        socket.push("""{"event":"ready","chat_id":"$chat","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
    }

    private fun attaches(sent: List<String>): List<String> = sent.filter { it.contains("\"attach\"") }

    @Test
    fun aNewSocketIsToldAboutTheChatAgain() = runTest {
        val core = core()
        val handle = core.chat(chat)

        connect(core, first)
        handle.open()
        runCurrent()
        first.push("""{"event":"attached","chat_id":"$chat"}""")
        runCurrent()
        assertEquals(1, attaches(first.sent).size, "the first socket attached the chat when it was opened")

        // The phone slept, the socket died, the app reconnects: a fresh token on a new socket.
        connect(core, second)
        second.sent.clear()

        handle.open()
        runCurrent()

        assertEquals(
            1,
            attaches(second.sent).size,
            "the new socket has never heard of this chat and must be told again: ${second.sent}",
        )
    }

    /**
     * What makes "Try again" worth offering: every connect bootstraps again, so the retry is a
     * real handshake on a token that has never been used. A cached token would fail the handshake
     * and the button would be theatre (C3.4, AGENTS.md gotcha 10).
     */
    @Test
    fun everyReconnectBootstrapsAFreshToken() = runTest {
        val core = core()

        connect(core, first)
        connect(core, second)

        assertEquals(
            listOf(
                "ws://127.0.0.1:18765/?client_id=nanobot-companion&token=tok-1",
                "ws://127.0.0.1:18765/?client_id=nanobot-companion&token=tok-2",
            ),
            handshakes,
            "the second handshake carries the token minted for it, not the consumed one",
        )
    }

    @Test
    fun aMessageSentAfterAReconnectAsksForTheAttachItNeeds() = runTest {
        val core = core()
        val handle = core.chat(chat)

        connect(core, first)
        handle.open()
        runCurrent()
        first.push("""{"event":"attached","chat_id":"$chat"}""")
        runCurrent()

        connect(core, second)
        second.sent.clear()

        assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "still there?")))
        runCurrent()

        assertTrue(attaches(second.sent).isNotEmpty(), "the attach goes first or the message is lost: ${second.sent}")
        assertTrue(
            second.sent.none { it.contains("still there?") },
            "and the message waits for the gateway's answer rather than overtaking it: ${second.sent}",
        )

        second.push("""{"event":"attached","chat_id":"$chat"}""")
        runCurrent()

        assertTrue(
            second.sent.last().contains("still there?"),
            "the message the attach was holding rides the new socket once it is attached: ${second.sent}",
        )
    }
}
