package de.arjmandi.nanobot.companion.engine

import de.arjmandi.nanobot.companion.projection.Effect
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.CoreError
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.transport.Socket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapPayload
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.Outbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The engine is the one place effects stop being data. Everything here runs on
 * a fake transport and the test dispatcher's virtual time: no real socket, no wall clock,
 * and every scenario ends by counting what is still running.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TurnEngineTest {

    private class RecordingHost : EngineHost() {
        val events: MutableList<String> = mutableListOf()
        val delegated: MutableList<Effect> = mutableListOf()
        var effects: List<Effect> = emptyList()
        var closedWith: CoreError? = null
        var closeCalls: Int = 0
        var payload: BootstrapPayload? = null

        override suspend fun opening(payload: BootstrapPayload) {
            this.payload = payload
        }

        override suspend fun frame(frame: Frame): List<Effect> {
            events += frame.event
            return effects
        }

        override suspend fun closed(error: CoreError?) {
            closeCalls += 1
            closedWith = error
        }

        override suspend fun effect(effect: Effect) {
            delegated += effect
        }
    }

    private fun payload(token: String): BootstrapPayload {
        val text = """
            {"token":"$token","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"version":"0.4.1","min_protocol":1,"max_protocol":1,
               "capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        return assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
    }

    private fun attempt(socket: Socket): ConnectionAttempt {
        val tokens = listOf("first-token", "second-token", "third-token").iterator()
        return ConnectionAttempt(
            bootstrap = { BootstrapOutcome.Ready(payload(tokens.next())) },
            openSocket = { socket },
        )
    }

    private fun envelopeTypes(sent: List<String>): List<String> = sent.map { text ->
        (Json.parseToJsonElement(text) as JsonObject)["type"]!!.jsonPrimitive.content
    }

    /** The fake socket buffers, so the greeting can be queued before the pump collects it. */
    private suspend fun connectReady(socket: FakeSocket, engine: TurnEngine): EngineOpen {
        socket.push(READY)
        return engine.connect("s3cr3t")
    }

    @Test
    fun connectsWhenTheGatewaySaysReady() = runTest {
        val socket = FakeSocket()
        val host = RecordingHost()
        val engine = TurnEngine(backgroundScope, attempt(socket), host)

        val outcome = connectReady(socket, engine)

        assertIs<EngineOpen.Ready>(outcome)
        assertTrue(engine.isOpen, "a ready socket is an open engine")
        assertEquals(listOf("ready"), host.events, "the greeting reaches the host before connect returns")
        assertEquals(1, socket.opened)
    }

    @Test
    fun sendsFramesThroughOneChannelInOrder() = runTest {
        val socket = FakeSocket()
        val engine = TurnEngine(backgroundScope, attempt(socket), RecordingHost())
        connectReady(socket, engine)

        engine.send(Outbound.NewChat)
        engine.send(Outbound.Attach(chatId = "websocket:a"))
        engine.send(Outbound.Attach(chatId = "websocket:b"))
        runCurrent()

        assertEquals(
            listOf("new_chat", "attach", "attach"),
            envelopeTypes(socket.sent),
            "one channel, one writer: frame order is the order the caller asked for",
        )
    }

    @Test
    fun executesSendEffectsProducedByTheReducer() = runTest {
        val socket = FakeSocket()
        val host = RecordingHost()
        val engine = TurnEngine(backgroundScope, attempt(socket), host)
        host.effects = listOf(Effect.Send(Outbound.Attach(chatId = "websocket:a")))

        connectReady(socket, engine)
        runCurrent()

        assertEquals(listOf("attach"), envelopeTypes(socket.sent), "an effect the engine owns goes on the wire")
        assertTrue(host.delegated.isEmpty())
    }

    @Test
    fun delegatesEffectsItCannotExecute() = runTest {
        val socket = FakeSocket()
        val host = RecordingHost()
        val engine = TurnEngine(backgroundScope, attempt(socket), host)
        host.effects = listOf(Effect.PersistCache(chatId = "websocket:a"))

        connectReady(socket, engine)
        runCurrent()

        assertEquals(
            listOf<Effect>(Effect.PersistCache(chatId = "websocket:a")),
            host.delegated,
            "persistence is the host's work, not the socket's",
        )
    }

    @Test
    fun closeLeavesNoJobsAndNoSocket() = runTest {
        val socket = FakeSocket()
        val engine = TurnEngine(backgroundScope, attempt(socket), RecordingHost())
        connectReady(socket, engine)

        engine.close()

        assertEquals(0, engine.activeChildJobs(), "teardown cancels and joins every job it started")
        assertEquals(1, socket.opened)
        assertEquals(1, socket.closed, "the socket flow's finally block is the only close path")
        assertFalse(engine.isOpen)
    }

    @Test
    fun neverReadyTimesOutAndClosesTheSocket() = runTest {
        val socket = FakeSocket()
        val engine = TurnEngine(backgroundScope, attempt(socket), RecordingHost())

        // Nothing greets us. Virtual time is what makes this test instant.
        val outcome = engine.connect("s3cr3t")

        assertEquals(CoreError.Timeout, assertIs<EngineOpen.Failed>(outcome).error)
        assertEquals(0, engine.activeChildJobs(), "a handshake that never settles must not leave a pump behind")
        assertEquals(1, socket.closed)
    }

    /** A receipt may not claim a frame the socket refused. The channel takes the frame, the
     * writer hands it on, and only the writer knows whether the socket wanted it. */
    @Test
    fun aWriteTheSocketRefusedIsNotASend() = runTest {
        val socket = FakeSocket()
        val engine = TurnEngine(backgroundScope, attempt(socket), RecordingHost())
        connectReady(socket, engine)
        socket.refusesWrites = true

        assertFalse(engine.send(Outbound.NewChat), "a frame the socket would not take is not a frame sent")
        assertEquals(emptyList<String>(), socket.sent)
    }

    @Test
    fun sendAfterCloseIsRefused() = runTest {
        val socket = FakeSocket()
        val engine = TurnEngine(backgroundScope, attempt(socket), RecordingHost())
        connectReady(socket, engine)

        engine.close()

        assertFalse(engine.send(Outbound.NewChat), "a closed engine refuses rather than buffering forever")
        assertEquals(emptyList<String>(), socket.sent)
    }

    @Test
    fun reconnectBootstrapsAgainWithAFreshToken() = runTest {
        val socket = FakeSocket()
        val host = RecordingHost()
        val engine = TurnEngine(backgroundScope, attempt(socket), host)

        connectReady(socket, engine)
        engine.close()
        connectReady(socket, engine)

        assertEquals(2, socket.opened, "a reconnect is a new socket, never a resume")
        assertEquals(1, socket.closed)
        assertEquals(listOf("ready", "ready"), host.events)
    }

    @Test
    fun reportsAClosedSocketWithoutTheClientAsking() = runTest {
        val socket = FakeSocket()
        val host = RecordingHost()
        val engine = TurnEngine(backgroundScope, attempt(socket), host)
        connectReady(socket, engine)

        socket.end()
        runCurrent()

        assertEquals(1, host.closeCalls, "the host is told the socket went away so the UI can stop pretending")
        assertEquals(null, host.closedWith, "a clean close is not an error")
        assertEquals(0, engine.activeChildJobs())
    }

    private companion object {
        const val READY: String = """{"event":"ready","chat_id":"websocket:a","client_id":"companion"}"""
    }
}
