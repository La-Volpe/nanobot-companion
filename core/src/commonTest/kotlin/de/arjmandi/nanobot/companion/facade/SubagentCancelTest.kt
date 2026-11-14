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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stopping a subtask (plan.md I8's dock, parity row `thread.subagents`).
 *
 * The gateway's one control is `subagent.cancel` over `webui_request` (`ws_http.py:202,931-955`),
 * with the session key and the task id — the same mutation shape as a recovery decision.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubagentCancelTest {

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
        socket.sent.clear()
        return core
    }

    @Test
    fun cancellingASubtaskSendsTheGatewaysOwnAction() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        var result: AutomationActionResult? = null
        val job = launch { result = handle.cancelSubagent(taskId = "sub-1") }
        runCurrent()

        val frame = Json.parseToJsonElement(socket.sent.single()) as JsonObject
        assertEquals("webui_request", frame["type"]!!.jsonPrimitive.content)
        assertEquals("subagent.cancel", frame["action"]!!.jsonPrimitive.content, "the action name is the gateway's")
        val payload = frame["payload"]!!.jsonObject
        assertEquals(chat, payload["session_key"]!!.jsonPrimitive.content, "the chat is the session key")
        assertEquals("sub-1", payload["task_id"]!!.jsonPrimitive.content)

        socket.push(
            """{"event":"webui_response","request_id":"${frame["request_id"]!!.jsonPrimitive.content}",
                "ok":true,"result":{"task_id":"sub-1","state":"stopping"}}""",
        )
        advanceUntilIdle()
        job.join()

        assertIs<AutomationActionResult.Accepted>(result, "the gateway answered, so the dock stops showing the task")
    }

    @Test
    fun aRefusedCancelKeepsTheTaskVisible() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        var result: AutomationActionResult? = null
        val job = launch { result = handle.cancelSubagent(taskId = "sub-1") }
        runCurrent()
        val frame = Json.parseToJsonElement(socket.sent.single()) as JsonObject
        socket.push(
            """{"event":"webui_response","request_id":"${frame["request_id"]!!.jsonPrimitive.content}",
                "ok":false,"error":{"status":404,"message":"task unavailable"}}""",
        )
        advanceUntilIdle()
        job.join()

        assertEquals(404, assertIs<AutomationActionResult.Refused>(result).status, "the gateway's own status, not a guess")
    }
}
