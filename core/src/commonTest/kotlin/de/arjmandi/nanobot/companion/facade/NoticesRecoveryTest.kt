package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.projection.Notice
import de.arjmandi.nanobot.companion.projection.SandboxIssue
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two notices a user acts on. Recovery is a socket mutation, `{chat_id, recovery_id}` on
 * `recovery.continue` / `recovery.dismiss`, because the gateway refuses those routes over plain
 * HTTP (`ws_http.py:900`). The sandbox warning is dismissed locally, because it is this client's
 * own observation of a failed `exec`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NoticesRecoveryTest {

    private val chat = "websocket:a"

    private suspend fun TestScope.core(socket: FakeSocket): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "api_token":"api","model_name":"deepseek-v4.1-flash:cloud",
             "terminal":{"webui":{"version":"0.4.1","min_protocol":1,"max_protocol":1,
               "capabilities":["webui.core.v1"]}}}
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
        runCurrent()
        socket.sent.clear()
        return core
    }

    private fun awaiting(socket: FakeSocket, id: String = "rec-1", canContinue: Boolean = true) {
        socket.push(
            """{"event":"recovery_state","chat_id":"$chat","recovery_id":"$id",
                "status":"awaiting_user","reason":"tool_state_uncertain","can_continue":$canContinue}""",
        )
    }

    @Test
    fun continueIsASocketMutationThatCarriesTheRecoveryId() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        awaiting(socket)
        runCurrent()

        var result: AutomationActionResult? = null
        val job = launch { result = handle.continueRecovery() }
        runCurrent()

        val frame = Json.parseToJsonElement(socket.sent.single()) as JsonObject
        assertEquals("webui_request", frame["type"]!!.jsonPrimitive.content)
        assertEquals("recovery.continue", frame["action"]!!.jsonPrimitive.content)
        val payload = frame["payload"]!!.jsonObject
        assertEquals(chat, payload["chat_id"]!!.jsonPrimitive.content)
        assertEquals("rec-1", payload["recovery_id"]!!.jsonPrimitive.content)

        socket.push(
            """{"event":"webui_response","request_id":"${frame["request_id"]!!.jsonPrimitive.content}","ok":true,"result":{}}""",
        )
        advanceUntilIdle()
        job.join()

        assertIs<AutomationActionResult.Accepted>(result)
    }

    @Test
    fun dismissIsItsOwnMutation() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        awaiting(socket)
        runCurrent()

        var result: AutomationActionResult? = null
        val job = launch { result = handle.dismissRecovery() }
        runCurrent()

        val frame = Json.parseToJsonElement(socket.sent.single()) as JsonObject
        assertEquals("recovery.dismiss", frame["action"]!!.jsonPrimitive.content)
        socket.push(
            """{"event":"webui_response","request_id":"${frame["request_id"]!!.jsonPrimitive.content}","ok":true,"result":{}}""",
        )
        advanceUntilIdle()
        job.join()
        assertIs<AutomationActionResult.Accepted>(result)
    }

    @Test
    fun aRefusedContinueKeepsTheNotice() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        awaiting(socket)
        runCurrent()

        var result: AutomationActionResult? = null
        val job = launch { result = handle.continueRecovery() }
        runCurrent()
        val frame = Json.parseToJsonElement(socket.sent.single()) as JsonObject
        socket.push(
            """{"event":"webui_response","request_id":"${frame["request_id"]!!.jsonPrimitive.content}",
                "ok":false,"error":{"status":409,"message":"recovery_not_available"}}""",
        )
        advanceUntilIdle()
        job.join()

        assertEquals(409, assertIs<AutomationActionResult.Refused>(result).status)
        assertTrue(
            handle.thread.value.notices.any { it is Notice.Recovery },
            "a refused continue leaves the decision on screen",
        )
    }

    @Test
    fun theRecoveryNoticeIsGoneOnceTheTurnResumes() = runTest {
        val socket = FakeSocket()
        val handle = core(socket).chat(chat)
        awaiting(socket)
        runCurrent()
        assertTrue(handle.thread.value.notices.any { it is Notice.Recovery })

        socket.push("""{"event":"recovery_state","chat_id":"$chat","recovery_id":"rec-1","status":"resuming"}""")
        runCurrent()

        assertTrue(handle.thread.value.notices.none { it is Notice.Recovery })
    }

    @Test
    fun theSandboxWarningIsDismissedOnTheHandle() = runTest {
        val socket = FakeSocket()
        val handle = core(socket).chat(chat)
        socket.push(
            """{"event":"message","chat_id":"$chat","turn_id":"t1","tool_events":[
                 {"name":"exec","phase":"error","error_code":"sandbox_path_unavailable","call_id":"c1"}]}""",
        )
        runCurrent()
        val sandbox = handle.thread.value.notices.filterIsInstance<Notice.Sandbox>().single()
        assertEquals(SandboxIssue.Path, sandbox.issue)

        handle.dismissNotice(sandbox.id)

        assertTrue(handle.thread.value.notices.none { it is Notice.Sandbox })
    }
}
