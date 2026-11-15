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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * A chat that does not exist yet (T29, T30). The shell asks the core for "the chat on screen"
 * twice — once to mark it for the notice rule, once for the screen itself — and it asks before
 * the greeting has minted an id on a cold launch. Whatever the core hands back has to be the chat
 * the socket fills, or the send goes into one session and the thread the reader looks at is
 * another: a skeleton that never resolves, and a prompt that is accepted and never drawn.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewChatTest {

    private val chat = "uuid-new"

    private suspend fun TestScope.core(socket: FakeSocket = FakeSocket()): NanobotCore {
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
        return core
    }

    @Test
    fun aBrandNewChatsThreadIsEmptyRatherThanUnread() = runTest {
        val core = core()

        val handle = core.chat(null)

        assertEquals(
            HistoryStatus.Loaded,
            handle.history.value,
            "no page is in flight for this chat, so its empty thread is a fact and not a wait",
        )
    }

    @Test
    fun theHandleTheShellGetsTwiceIsTheChatTheSocketFills() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        // The shell's two asks: `markOnScreen` then the screen's own lookup, both before any frame.
        core.chat(null)
        val handle = core.chat(null)

        val receipt = handle.send(OutboundDraft(text = "hello"))
        assertIs<SendReceipt.Accepted>(receipt, "the greeting minted this chat, so the send has somewhere to go")

        assertEquals(
            "hello",
            handle.thread.value.turns.singleOrNull()?.prompt?.text,
            "the accepted prompt is drawn at once: the bubble is the core's, on the handle the screen holds",
        )
    }

    @Test
    fun aNewChatHandleStillAnswersWhenTheGreetingHasNotArrived() = runTest {
        // A shell that appears before the socket is open: no chat id yet, and nothing to read.
        val socket = FakeSocket()
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
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(bootstrap()) }, { socket }),
            http = null,
        )
        val before = core.chat(null)
        assertEquals(HistoryStatus.Loaded, before.history.value, "nothing has been asked for, so nothing is loading")

        socket.push("""{"event":"ready","chat_id":"$chat","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())

        assertEquals(chat, before.id.value, "the greeting binds the very handle the screen is holding")

        before.send(OutboundDraft(text = "hello"))
        assertEquals(
            "hello",
            before.thread.value.turns.singleOrNull()?.prompt?.text,
            "and the same handle is the one a send fills",
        )
    }

    private fun bootstrap(): de.arjmandi.nanobot.companion.wire.BootstrapPayload {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        return assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
    }
}
