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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The queued prompt, and the chip above the field. The WebUI's rule is
 * `showStopButton = isStreaming && !canSend`: typing while a turn runs makes the button Send
 * again, and pressing it queues the prompt instead of stopping the turn (`useQueuedPrompt.ts`).
 * The queue is the client's, because the gateway has nowhere to hold a prompt for later, and it
 * releases on the turn's `turn_end`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerQueueTest {

    private val chat = "websocket:a"

    private suspend fun TestScope.core(socket: FakeSocket): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}},
             "limits":{"transport":{"max_frame_bytes":37748736},"message":{"max_text_bytes":65536}}}
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
        socket.sent.clear()
        return core
    }

    /**
     * A turn in flight, as the gateway announces one. It is pushed after the handle exists,
     * because that is the order a screen produces: the chat is opened, then frames arrive.
     */
    private fun FakeSocket.startTurn() {
        push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1","client_turn_id":"turn-1"}""")
        push("""{"event":"user_message","chat_id":"$chat","text":"first","turn_id":"turn-1","starts_turn":true}""")
    }

    @Test
    fun aQueuedPromptWaitsAndThenSendsItself() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        // The screen opens the chat before a turn exists; the attach is what the release rides.
        handle.open()
        socket.push("""{"event":"attached","chat_id":"$chat"}""")
        socket.startTurn()
        runCurrent()
        socket.sent.clear()

        handle.enqueue("and then check the tests")
        runCurrent()

        assertEquals("and then check the tests", handle.composer.value.queued, "the chip has a prompt to show")
        assertTrue(socket.sent.isEmpty(), "a queued prompt sends nothing while the turn runs")

        socket.push("""{"event":"turn_end","chat_id":"$chat","turn_id":"turn-1","outcome":"success"}""")
        runCurrent()

        assertNull(handle.composer.value.queued, "the settled turn released it")
        val released = socket.sent.filter { it.contains("\"message\"") }
        assertEquals(1, released.size, "one message went out: ${socket.sent}")
        assertTrue(released.single().contains("and then check the tests"), released.single())
        assertEquals(
            "and then check the tests",
            handle.thread.value.turns.last().prompt?.text,
            "the released prompt is in the thread as an optimistic bubble",
        )
    }

    @Test
    fun aSecondQueuedPromptJoinsTheFirst() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        socket.startTurn()
        runCurrent()

        handle.enqueue("first thought")
        handle.enqueue("second thought")

        assertEquals(
            "first thought\nsecond thought",
            handle.composer.value.queued,
            "the WebUI merges what is typed while a prompt waits (useQueuedPrompt.mergeQueuedPrompt)",
        )
    }

    @Test
    fun sendNowSteersTheRunningTurn() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        socket.startTurn()
        runCurrent()
        handle.enqueue("also check the docs")
        socket.sent.clear()

        val receipt = handle.sendQueuedNow()
        runCurrent()

        assertIs<SendReceipt.Accepted>(receipt)
        assertNull(handle.composer.value.queued, "Send now takes the prompt off the chip")
        val sent = socket.sent.single()
        assertTrue(sent.contains("also check the docs"), sent)
        assertTrue(
            sent.contains("\"turn_id\":\"turn-1\"") || sent.contains("\"turn_id\": \"turn-1\""),
            "Send now steers the running turn rather than opening a second one: $sent",
        )
    }

    @Test
    fun deleteDropsTheQueuedPrompt() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        handle.enqueue("never mind")

        handle.discardQueued()

        assertNull(handle.composer.value.queued)
        assertTrue(socket.sent.none { it.contains("never mind") }, "Delete sends nothing")
    }

    @Test
    fun theQueueBelongsToTheChatItWasTypedIn() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val other = core.chat("websocket:b")
        val handle = core.chat(chat)

        handle.enqueue("for this chat")
        runCurrent()

        assertNull(other.composer.value.queued, "another chat's chip is not this chat's queue")
        assertEquals("for this chat", handle.composer.value.queued)
    }
}
