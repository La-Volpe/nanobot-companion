package de.arjmandi.nanobot.companion.engine

import de.arjmandi.nanobot.companion.facade.ConnectionState
import de.arjmandi.nanobot.companion.facade.ConnectResult
import de.arjmandi.nanobot.companion.facade.GatewayProfile
import de.arjmandi.nanobot.companion.facade.NanobotCore
import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.transport.Socket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A phone loses its socket constantly: tunnels, sleep, a gateway restart. Every reconnect
 * must leave nothing behind, and the only honest way to show that is a storm: a hundred
 * sockets that each close themselves after three frames, then a count of what is still
 * running.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SoakTest {

    private val chat = "websocket:storm"

    /** Frames per connection before the gateway drops it, as a flaky link would. */
    private val framesPerConnection = 3

    private class Storm {
        var opened: Int = 0
        var closed: Int = 0
        var frames: Int = 0
        val sockets: MutableList<FakeSocket> = mutableListOf()

        /** A socket that greets, says one thing, then closes itself. */
        fun next(): Socket {
            val socket = FakeSocket()
            socket.push("""{"event":"ready","chat_id":"websocket:storm","client_id":"companion"}""")
            socket.push("""{"event":"delta","chat_id":"websocket:storm","turn_id":"turn-1","stream_id":"s1","text":"storm "}""")
            socket.push("""{"event":"stream_end","chat_id":"websocket:storm","turn_id":"turn-1","stream_id":"s1"}""")
            socket.end()
            sockets += socket
            frames += 3
            return socket
        }
    }

    private suspend fun TestScope.core(storm: Storm): NanobotCore {
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
        return NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt(
                bootstrap = { BootstrapOutcome.Ready(payload) },
                openSocket = { _ -> storm.next() },
            ),
            http = null,
        )
    }

    @Test
    fun hundredReconnectsLeaveZeroJobs() = runTest {
        val storm = Storm()
        val core = core(storm)
        val handle = core.chat(chat)

        repeat(RECONNECTS) {
            assertIs<ConnectResult.Connected>(core.connect())
            runCurrent()
            // The gateway drops the socket on its own, which is the shape a flaky link has.
            core.connection.value.let { state ->
                assertTrue(
                    state is ConnectionState.Open || state is ConnectionState.Offline,
                    "a socket is either open or gone, never stuck connecting: $state",
                )
            }
            handle.thread.value
        }

        assertEquals(RECONNECTS, storm.sockets.size, "every reconnect is a socket of its own")
        assertEquals(RECONNECTS, storm.sockets.count { it.opened == 1 }, "each socket was opened exactly once")
        assertEquals(RECONNECTS, storm.sockets.count { it.closed == 1 }, "and closed exactly once, by the pump")
        assertEquals(0, core.activeChildJobs(), "a storm leaves no job behind")

        core.disconnect(forget = false)
        assertEquals(0, core.activeChildJobs())
    }

    @Test
    fun stormOfConnectAndDisconnectLeavesNothing() = runTest {
        val storm = Storm()
        val core = core(storm)

        repeat(RECONNECTS) {
            assertIs<ConnectResult.Connected>(core.connect())
            runCurrent()
            core.disconnect(forget = false)
        }

        assertTrue(storm.sockets.all { it.closed == 1 }, "no socket outlives the disconnect that asked for it")
        assertEquals(0, core.activeChildJobs())
        assertEquals(ConnectionState.Idle, core.connection.value)
    }

    private companion object {
        /** A hundred reconnects: enough that one leaked job per attempt would be obvious. */
        const val RECONNECTS: Int = 100
    }
}
