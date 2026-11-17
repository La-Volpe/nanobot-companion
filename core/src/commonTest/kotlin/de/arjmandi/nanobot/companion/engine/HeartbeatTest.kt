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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A socket that dies without a FIN, a close frame or an error: the phone changed
 * networks, or the gateway's host stopped answering, so `incoming` never ends and the dot stays
 * green over a connection that cannot carry anything. The heartbeat is the engine's own answer
 * to that, and everything here runs on virtual time with a fake transport.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeartbeatTest {

    /** Seconds in the real policy; milliseconds here, so the assertions read as one interval. */
    private val policy = HeartbeatPolicy(intervalMs = 1_000, windowMs = 200)

    private class RecordingHost : EngineHost() {
        var closedWith: CoreError? = null
        var closeCalls: Int = 0

        override suspend fun opening(payload: BootstrapPayload) = Unit

        override suspend fun frame(frame: Frame): List<Effect> = emptyList()

        override suspend fun closed(error: CoreError?) {
            closeCalls += 1
            closedWith = error
        }

        override suspend fun effect(effect: Effect) = Unit
    }

    private fun payload(token: String): BootstrapPayload {
        val text = """
            {"token":"$token","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"version":"0.4.1","min_protocol":1,"max_protocol":1,
               "capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        return assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
    }

    private fun attempt(socket: Socket): ConnectionAttempt =
        ConnectionAttempt(bootstrap = { BootstrapOutcome.Ready(payload("token")) }, openSocket = { socket })

    private fun attaches(sent: List<String>): List<String> = sent.filter { text ->
        (Json.parseToJsonElement(text) as JsonObject)["type"]!!.jsonPrimitive.content == "attach"
    }

    /** A gateway that answers every `attach` it is sent, which is the healthy socket. */
    private fun answering(socket: FakeSocket) {
        socket.answer = { text ->
            if (attaches(listOf(text)).isNotEmpty()) socket.push(ATTACHED)
        }
    }

    private suspend fun TestScope.connected(socket: FakeSocket): Pair<TurnEngine, RecordingHost> {
        val recording = RecordingHost()
        val engine = TurnEngine(backgroundScope, attempt(socket), recording, heartbeat = policy)
        socket.push(READY)
        assertIs<EngineOpen.Ready>(engine.connect("s3cr3t"))
        return engine to recording
    }

    @Test
    fun theFirstQuestionWaitsOneIntervalAndNoLonger() = runTest {
        val socket = FakeSocket()
        val (engine, host) = connected(socket)
        answering(socket)

        engine.setWatched(true)
        runCurrent()
        assertEquals(1, attaches(socket.sent).size, "a return to the foreground asks at once")
        socket.sent.clear()

        advanceTimeBy(policy.intervalMs - 1)
        runCurrent()
        assertEquals(0, attaches(socket.sent).size, "a socket is given the interval before it is asked anything")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, attaches(socket.sent).size, "then the question goes out, once")
        assertEquals(0, host.closeCalls)
    }

    @Test
    fun aSocketThatStopsAnsweringLosesTheConnection() = runTest {
        val socket = FakeSocket()
        val (engine, host) = connected(socket)
        engine.setWatched(true)

        // Nothing answers, and nothing ever ends the flow: this is the silent death.
        advanceTimeBy(policy.intervalMs + policy.windowMs + 1)
        runCurrent()

        assertEquals(1, host.closeCalls, "the host is told, so the dot cannot stay green")
        assertEquals(CoreError.Timeout, host.closedWith, "a question that was never answered is a timeout")
        assertFalse(engine.isOpen, "sends are refused from here")
        assertEquals(0, engine.activeChildJobs(), "and nothing the socket started is left running")
    }

    @Test
    fun aSocketThatKeepsAnsweringStaysOpen() = runTest {
        val socket = FakeSocket()
        val (engine, host) = connected(socket)
        answering(socket)

        engine.setWatched(true)
        runCurrent()
        socket.sent.clear()
        advanceTimeBy(policy.intervalMs * 3 + 1)
        runCurrent()

        assertEquals(0, host.closeCalls, "an answered heartbeat is not a failure")
        assertTrue(engine.isOpen)
        assertEquals(3, attaches(socket.sent).size, "one question per interval, not a stream of them")
    }

    @Test
    fun aReturnAsksAtOnceAndFlipsOnSilence() = runTest {
        val socket = FakeSocket()
        val (engine, host) = connected(socket)
        engine.setWatched(false)

        // The app was away; no timer ran, and nothing has been read from this socket.
        advanceTimeBy(policy.intervalMs * 3)
        runCurrent()
        assertEquals(0, attaches(socket.sent).size, "a pocketed phone is asked nothing")

        engine.setWatched(true)
        runCurrent()

        assertEquals(1, attaches(socket.sent).size, "a return asks now instead of waiting out the interval")
        assertEquals(0, host.closeCalls, "the window is still open")

        advanceTimeBy(policy.windowMs + 1)
        runCurrent()

        assertEquals(1, host.closeCalls, "an unanswered return is the same death as an unanswered tick")
        assertEquals(CoreError.Timeout, host.closedWith)
    }

    @Test
    fun aDeliberateCloseIsNotReportedAsADeath() = runTest {
        val socket = FakeSocket()
        val (engine, host) = connected(socket)
        engine.setWatched(true)

        engine.close()
        advanceTimeBy(policy.intervalMs * 4)
        engine.setWatched(true)
        runCurrent()

        assertEquals(0, host.closeCalls, "the client closed this socket; it did not die")
        assertEquals(0, attaches(socket.sent).size, "and a closed engine asks nothing")
    }

    private companion object {
        const val READY: String = """{"event":"ready","chat_id":"websocket:a","client_id":"companion"}"""
        const val ATTACHED: String = """{"event":"attached","chat_id":"websocket:a"}"""
    }
}
