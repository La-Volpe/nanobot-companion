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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Access mode is `set_workspace_scope` with `default` or `full`, and the
 * gateway can refuse it: `detail: "workspace_scope_rejected"` (inbound_commands.py:283). A
 * refused switch must put the chip back where it was rather than lie about the sandbox.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccessModeTest {

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
        socket.sent.clear()
        return core
    }

    @Test
    fun fullSendsSetWorkspaceScope() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        handle.setWorkspaceScope(WorkspaceScope.Full)
        runCurrent()

        val sent = Json.parseToJsonElement(socket.sent.single()) as JsonObject
        assertEquals("set_workspace_scope", sent["type"]!!.jsonPrimitive.content)
        assertEquals("full", sent["access_mode"]!!.jsonPrimitive.content, "the gateway takes exactly `full` or `default`")
        assertEquals(chat, sent["chat_id"]!!.jsonPrimitive.content)
        assertEquals(WorkspaceScope.Full, handle.composer.value.accessMode, "the chip follows the switch immediately")
    }

    @Test
    fun rejectedScopeRestoresPrevious() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        assertTrue(handle.composer.value.accessMode == WorkspaceScope.Default)

        handle.setWorkspaceScope(WorkspaceScope.Full)
        runCurrent()
        socket.push("""{"event":"error","chat_id":"$chat","detail":"workspace_scope_rejected"}""")
        advanceUntilIdle()

        assertEquals(WorkspaceScope.Default, handle.composer.value.accessMode, "a refused scope puts the chip back")
    }

    @Test
    fun anUnrelatedErrorLeavesTheScopeAlone() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        handle.setWorkspaceScope(WorkspaceScope.Full)
        runCurrent()
        socket.push("""{"event":"error","chat_id":"$chat","detail":"message too large"}""")
        advanceUntilIdle()

        assertEquals(WorkspaceScope.Full, handle.composer.value.accessMode, "only the scope's own rejection reverses it")
    }
}
