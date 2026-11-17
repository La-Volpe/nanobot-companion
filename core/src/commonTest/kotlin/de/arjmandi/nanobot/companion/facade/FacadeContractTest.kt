package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.projection.TurnStatus
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapPayload
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
 * The public surface. Everything here is driven through [NanobotCore] and a
 * fake transport, which is the claim that matters: the facade is composable from fakes with
 * no platform API in sight, and a chat can be driven without a gateway.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FacadeContractTest {

    private val chat = "websocket:a"

    private suspend fun payload(): BootstrapPayload {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "model_name":"deepseek-v4.1-flash:cloud","api_token":"api-token",
             "terminal":{"webui":{"version":"0.4.1","min_protocol":1,"max_protocol":1,
               "capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        return assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
    }

    private fun TestScope.deps(files: InMemoryFileStore = InMemoryFileStore()): CoreDeps = CoreDeps(
        clock = FakeClock(startMs = 1_000),
        secureStore = InMemorySecureStore(),
        files = files,
        appInfo = FakeAppInfo(),
        // The test's own scheduler, so the engine's timers move with virtual time.
        dispatchers = CoreDispatchers(
            compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            main = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
    )

    private suspend fun TestScope.core(socket: FakeSocket, files: InMemoryFileStore = InMemoryFileStore()): NanobotCore {
        val payload = payload()
        val deps = deps(files)
        return NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(payload) }, { socket }),
            http = null,
        )
    }

    @Test
    fun everyFlowIsReadableBeforeTheFirstConnect() = runTest {
        val core = core(FakeSocket())

        assertEquals(ConnectionState.Idle, core.connection.value, "a screen can read state before connect, not only after")
        assertEquals(emptyList(), core.sessions.value.rows)
        assertEquals(false, core.sessions.value.loaded)

        val handle = core.chat(chat)
        assertEquals(chat, handle.id.value, "an existing chat has the id it was opened with")
        assertNull(handle.thread.value.activeTurnId)
        assertEquals(WorkspaceScope.Default, handle.composer.value.accessMode, "the access chip defaults to default")
    }

    @Test
    fun connectBindsTheNewChatFromReady() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(null)
        assertNull(handle.id.value, "a new chat has no id until the gateway mints one")

        socket.push("""{"event":"ready","chat_id":"$chat","client_id":"companion"}""")
        val connected = core.connect()

        assertIs<ConnectResult.Connected>(connected)
        assertEquals(ConnectionState.Open, core.connection.value)
        assertEquals(chat, handle.id.value, "ready is what mints the chat, so the handle learns it here")
    }

    @Test
    fun sendBeforeConnectIsRejected() = runTest {
        val core = core(FakeSocket())
        val handle = core.chat(chat)

        val receipt = handle.send(OutboundDraft(text = "hello"))

        assertEquals(
            SendReceipt.Rejected(SendRejection.NotConnected),
            receipt,
            "a draft that cannot be delivered is refused, never queued forever",
        )
    }

    @Test
    fun anEmptyDraftNeverReachesTheSocket() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connectChat(socket, core)

        val receipt = core.chat(chat).send(OutboundDraft(text = "   \n "))

        assertEquals(SendReceipt.Rejected(SendRejection.EmptyDraft), receipt)
        assertTrue(socket.sent.isEmpty(), "whitespace is not a prompt")
    }

    @Test
    fun aSendAttachesTheChatFirst() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connectChat(socket, core)
        socket.sent.clear()

        val receipt = core.chat(chat).send(OutboundDraft(text = "hello"))

        assertIs<SendReceipt.Accepted>(receipt)
        runCurrent()
        assertEquals(
            listOf("attach"),
            socket.sent.map(::envelopeType),
            "an unattached chat is attached before its message is allowed through",
        )
    }

    @Test
    fun anAttachedChatSendsItsMessageDirectly() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connectChat(socket, core)
        socket.push("""{"event":"attached","chat_id":"$chat"}""")
        runCurrent()
        socket.sent.clear()

        val handle = core.chat(chat)
        val receipt = assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "hello")))
        runCurrent()

        assertEquals(listOf("message"), socket.sent.map(::envelopeType))
        assertTrue(socket.sent.single().contains("\"content\":\"hello\""), "the draft is what goes out")
        assertTrue(receipt.turnId.startsWith("companion-1000-"), "the client names its own turn, stamped with the injected clock")
    }

    @Test
    fun aStreamingAnswerLandsInTheThread() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connectChat(socket, core)
        val handle = core.chat(chat)

        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1","active_turn_id":"turn-1"}""")
        socket.push("""{"event":"delta","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1","text":"po"}""")
        socket.push("""{"event":"delta","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1","text":"ng"}""")
        socket.push("""{"event":"stream_end","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1"}""")
        socket.push("""{"event":"turn_end","chat_id":"$chat","turn_id":"turn-1","outcome":"completed"}""")
        runCurrent()

        val thread = handle.thread.value
        assertEquals("pong", thread.turn("turn-1")?.answer?.text, "the reducer ran inside the facade, not in a test")
        assertEquals(TurnStatus.Complete, thread.turn("turn-1")?.status)
        assertNull(thread.activeTurnId, "a settled turn releases the stop button")
    }

    @Test
    fun twoHandlesForOneChatShareState() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connectChat(socket, core)
        val first = core.chat(chat)
        val second = core.chat(chat)

        socket.push("""{"event":"delta","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1","text":"shared"}""")
        runCurrent()

        assertEquals("shared", second.thread.value.turn("turn-1")?.answer?.text, "a second handle shows the same thread")
        assertEquals(first.thread.value, second.thread.value)
    }

    @Test
    fun theThreadCacheIsWrittenByTheEngineEffect() = runTest {
        val socket = FakeSocket()
        val files = InMemoryFileStore()
        val core = core(socket, files)
        connectChat(socket, core)
        val handle = core.chat(chat)

        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"turn-1"}""")
        socket.push("""{"event":"delta","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1","text":"cached answer"}""")
        socket.push("""{"event":"stream_end","chat_id":"$chat","turn_id":"turn-1","stream_id":"s1"}""")
        runCurrent()

        val cached = files.values.entries.single { it.key.startsWith("thread-") }
        assertTrue(cached.value.contains("cached answer"), "the effect the reducer emitted was executed: ${cached.key}")
    }

    @Test
    fun disconnectForgetsTheStoredKey() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        core.deps.secureStore.put(GATEWAY_SECRET_KEY, "s3cr3t")
        connectChat(socket, core)

        core.disconnect(forget = true)

        assertNull(core.deps.secureStore.get(GATEWAY_SECRET_KEY), "Settings → Gateway has to be able to forget the key")
        assertEquals(ConnectionState.Idle, core.connection.value)
        assertEquals(0, core.activeChildJobs())
        assertEquals(1, socket.closed, "no socket survives a disconnect")
    }

    @Test
    fun aSocketThatGoesAwayLeavesTheCoreOffline() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        connectChat(socket, core)

        socket.end()
        runCurrent()

        assertEquals(ConnectionState.Offline, core.connection.value, "a lost socket is a state the banner can show")
        assertEquals(0, core.activeChildJobs())
    }

    private suspend fun TestScope.connectChat(socket: FakeSocket, core: NanobotCore) {
        socket.push("""{"event":"ready","chat_id":"$chat","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
    }

    private fun envelopeType(text: String): String =
        (kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)["type"]
            .let { assertIs<kotlinx.serialization.json.JsonPrimitive>(it).content }
}
