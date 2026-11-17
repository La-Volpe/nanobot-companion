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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Automations. The list is an HTTP read, but every action is a socket mutation,
 * `{type:"webui_request", request_id, action, payload}` answered by a `webui_response` frame
 * (`inbound_commands.py:930-943, 1219`), because the gateway refuses WebUI writes over plain
 * HTTP with a 405. And there is no create: an automation is created by asking the agent, which
 * the design states as a limit rather than hiding.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomationsTest {

    private val jobs = """
        {"jobs":[
          {"id":"heartbeat","name":"heartbeat","enabled":true,"protected":true,"delete_after_run":false,
           "schedule":{"kind":"every","at_ms":null,"every_ms":1800000,"expr":null,"tz":"Europe/Berlin"},
           "payload":{"message":"","kind":"system_event"},
           "state":{"next_run_at_ms":1794628371698,"last_status":null,"pending":false,
                    "last_run_at_ms":null,"last_error":null,"run_history":[]},
           "created_at_ms":1794627582219,"updated_at_ms":1794627582219,"origin":null},
          {"id":"weekly-digest","name":"Weekly digest","enabled":true,"protected":false,
           "delete_after_run":false,
           "schedule":{"kind":"cron","at_ms":null,"every_ms":null,"expr":"0 9 * * 1","tz":"Europe/Berlin"},
           "payload":{"message":"Summarise the week","kind":"agent_turn"},
           "state":{"next_run_at_ms":1794700000000,"last_status":"ok","pending":false,
                    "last_run_at_ms":1794600000000,"last_error":null,
                    "run_history":[{"run_at_ms":1794600000000,"status":"ok"}]},
           "created_at_ms":1794627582219,"updated_at_ms":1794627582219,"origin":"webui"}]}
    """.trimIndent()

    private fun disabled(): String = jobs.replace(
        """"id":"weekly-digest","name":"Weekly digest","enabled":true""",
        """"id":"weekly-digest","name":"Weekly digest","enabled":false""",
    )

    private fun engine(body: () -> String = { jobs }): MockEngine = MockEngine {
        respond(body(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    /** A gateway that refuses the read, the way a stale `api_token` does. */
    private fun refusingEngine(): MockEngine = MockEngine {
        respond("""{"detail":"bad token"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private suspend fun TestScope.core(socket: FakeSocket, engine: MockEngine = engine()): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "api_token":"api","model_name":"deepseek-v4.1-flash:cloud",
             "terminal":{"webui":{"version":"0.4.1","min_protocol":1,"max_protocol":1,
               "capabilities":["webui.core.v1","webui.automation-chat.v1"]}}}
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
            http = HttpClient(engine),
        )
        socket.push("""{"event":"ready","chat_id":"websocket:a","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
        socket.sent.clear()
        return core
    }

    private fun sentRequest(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun listsJobs() = runTest {
        val core = core(FakeSocket())

        assertNull(core.refreshAutomations())

        val listed = core.automations.value.jobs
        assertEquals(listOf("heartbeat", "weekly-digest"), listed.map { it.id })
        assertEquals("Weekly digest", listed[1].name)
        assertEquals("cron", listed[1].schedule.kind)
        assertEquals("0 9 * * 1", listed[1].schedule.expr)
        assertEquals(1_794_700_000_000L, listed[1].state.nextRunAtMs)
        assertEquals("ok", listed[1].state.lastStatus)
        assertTrue(listed[0].protected, "a system automation says so, and the UI hides its switch")
        assertTrue(!listed[1].protected)
    }

    /**
     * The owner's phone said "No automations yet" while the gateway had four, and the page
     * "disappears sometimes". Measured against their gateway on 2026-11-15: the global
     * `GET /api/webui/automations` returns the four, and every per-chat
     * `/api/sessions/{key}/automations` returns `{jobs: []}`, so the endpoint is right and the
     * page was blanked by a reconnect (`disconnect` empties this state) that nothing re-read.
     */
    @Test
    fun aReconnectReadsTheAutomationsAgain() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        assertNull(core.refreshAutomations())
        assertEquals(2, core.automations.value.jobs.size)

        core.disconnect(forget = false)
        runCurrent()
        assertTrue(core.automations.value.jobs.isEmpty(), "a new socket knows nothing until it asks")

        socket.push("""{"event":"ready","chat_id":"websocket:a","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())

        // The read runs beside the pump (an HTTP round trip must not hold up a handshake), so the
        // test waits on the state it fills rather than on a dispatcher's idea of "idle".
        val read = withContext(Dispatchers.Default) {
            withTimeout(5_000) { core.automations.first { it.jobs.isNotEmpty() } }
        }

        assertEquals(
            listOf("heartbeat", "weekly-digest"),
            read.jobs.map { it.id },
            "the socket came back, so the page's read comes back with it",
        )
    }

    /**
     * A refused read is not an empty gateway. The page needs to tell them apart, so the state
     * carries the answer instead of leaving an empty list that reads as "nothing scheduled".
     */
    @Test
    fun aRefusedReadIsRecordedAsAFailure() = runTest {
        val socket = FakeSocket()
        val core = core(socket, refusingEngine())

        assertEquals(de.arjmandi.nanobot.companion.transport.CoreError.AuthFailed, core.refreshAutomations())

        val state = core.automations.value
        assertTrue(state.jobs.isEmpty())
        assertTrue(state.failed, "the gateway said no, so the page says it could not read, not 'none'")
    }

    @Test
    fun enableDisableRunDelete() = runTest {
        for ((kind, wire) in listOf(
            AutomationActionKind.Enable to "automation.enable",
            AutomationActionKind.Disable to "automation.disable",
            AutomationActionKind.Run to "automation.run",
            AutomationActionKind.Delete to "automation.delete",
        )) {
            val socket = FakeSocket()
            val core = core(socket)
            var result: AutomationActionResult? = null
            val job = launch { result = core.runAutomation("weekly-digest", kind) }
            runCurrent()

            val frame = sentRequest(socket.sent.single())
            assertEquals("webui_request", frame["type"]!!.jsonPrimitive.content)
            assertEquals(wire, frame["action"]!!.jsonPrimitive.content)
            assertEquals("weekly-digest", frame["payload"]!!.jsonObject["id"]!!.jsonPrimitive.content)

            socket.push(response(frame["request_id"]!!.jsonPrimitive.content, if (kind == AutomationActionKind.Disable) disabled() else jobs))
            advanceUntilIdle()
            job.join()

            assertIs<AutomationActionResult.Done>(result, "$kind: $result")
            assertEquals(kind == AutomationActionKind.Disable, !core.automations.value.jobs[1].enabled, "$kind refreshes the list")
        }
    }

    @Test
    fun changeChat() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val before = core.automations.value.jobs.map { it.id }
        var result: AutomationActionResult? = null
        val job = launch {
            result = core.runAutomation(
                id = "weekly-digest",
                action = AutomationActionKind.ChangeChat,
                chatChange = AutomationChatChange(targetId = "chat-2", revision = "rev-7", message = "Summarise the week"),
            )
        }
        runCurrent()

        val frame = sentRequest(socket.sent.single())
        assertEquals("automation.change_chat", frame["action"]!!.jsonPrimitive.content)
        val payload = frame["payload"]!!.jsonObject
        assertEquals("weekly-digest", payload["id"]!!.jsonPrimitive.content)
        val values = payload["values"]!!.jsonObject
        assertEquals("chat-2", values["target_id"]!!.jsonPrimitive.content)
        assertEquals("rev-7", values["revision"]!!.jsonPrimitive.content)
        assertEquals("Summarise the week", values["message"]!!.jsonPrimitive.content)

        socket.push(response(frame["request_id"]!!.jsonPrimitive.content, jobs))
        advanceUntilIdle()
        job.join()
        assertIs<AutomationActionResult.Done>(result)
    }

    @Test
    fun aRefusedActionCarriesTheGatewaysOwnStatusAndWords() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        var result: AutomationActionResult? = null
        val job = launch { result = core.runAutomation("heartbeat", AutomationActionKind.Run) }
        runCurrent()

        val frame = sentRequest(socket.sent.single())
        socket.push(
            """{"event":"webui_response","request_id":"${frame["request_id"]!!.jsonPrimitive.content}",
                "ok":false,"error":{"status":403,"message":"system automation is protected"}}""",
        )
        advanceUntilIdle()
        job.join()

        val refused = assertIs<AutomationActionResult.Refused>(result)
        assertEquals(403, refused.status)
        assertEquals("system automation is protected", refused.message)
    }

    @Test
    fun noAnswerIsATimeoutNotAHang() = runTest {
        val socket = FakeSocket()
        // A refused read keeps the page empty, which is what "does not invent" is measured against.
        val core = core(socket, refusingEngine())
        var result: AutomationActionResult? = null
        val job = launch { result = core.runAutomation("weekly-digest", AutomationActionKind.Run) }
        runCurrent()

        advanceTimeBy(21_000)
        advanceUntilIdle()
        job.join()

        assertIs<AutomationActionResult.Timeout>(result)
        assertTrue(core.automations.value.jobs.isEmpty(), "a timeout does not invent a list")
    }

    @Test
    fun creationIsAgentMediatedSoThereIsNoCreateAction() {
        // `ws_http.py` `_WEBUI_MUTATION_PATHS` allowlists exactly these automation actions:
        // the app can list, switch, run, delete and re-bind a chat, and nothing creates one.
        assertEquals(
            listOf(
                "automation.enable",
                "automation.disable",
                "automation.run",
                "automation.delete",
                "automation.change_chat",
            ),
            AutomationActionKind.entries.map { it.wire },
            "a create action would be a button with no endpoint behind it (design README)",
        )
    }

    private fun response(requestId: String, body: String): String =
        """{"event":"webui_response","request_id":"$requestId","ok":true,"result":$body}"""
}
