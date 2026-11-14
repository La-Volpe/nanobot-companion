package de.arjmandi.nanobot.companion.contract

import de.arjmandi.nanobot.companion.facade.AutomationActionResult
import de.arjmandi.nanobot.companion.facade.AutomationActionKind
import de.arjmandi.nanobot.companion.facade.ChatHandle
import de.arjmandi.nanobot.companion.facade.ConnectResult
import de.arjmandi.nanobot.companion.facade.NanobotCore
import de.arjmandi.nanobot.companion.facade.OutboundDraft
import de.arjmandi.nanobot.companion.facade.SendReceipt
import de.arjmandi.nanobot.companion.projection.HistoryReplay
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.TurnStatus
import de.arjmandi.nanobot.companion.wire.AutomationsParser
import de.arjmandi.nanobot.companion.wire.FrameCodec
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

/**
 * The two G1 items that need more than one client or a mutation (plan.md §6):
 *
 * 1. two simultaneous clients on one gateway, interleaved sends, no cross-talk; and
 * 2. the `webui_request` / `webui_response` round trip, which only a live gateway can prove —
 *    every automation on the dev gateway is a protected `system_event`, so the answer is a
 *    403 and that is exactly what the client has to keep.
 */
class LiveTwoClientsTest {

    private val contract: Boolean = System.getProperty("nanobot.contract") == "true"

    private val profile: JsonObject by lazy {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, ".dev/profile.json") }
            .firstOrNull { it.isFile }
            ?: error(".dev/profile.json not found; run tools/nanobot-dev.sh start")
        Json.parseToJsonElement(file.readText()) as JsonObject
    }

    private fun core(): NanobotCore = NanobotCore(
        profile = de.arjmandi.nanobot.companion.facade.GatewayProfile(
            url = profile["url"]!!.jsonPrimitive.content,
            secret = profile["secret"]!!.jsonPrimitive.content,
        ),
        deps = de.arjmandi.nanobot.companion.platform.CoreDeps(
            clock = de.arjmandi.nanobot.companion.platform.systemClock(),
            secureStore = de.arjmandi.nanobot.companion.platform.InMemorySecureStore(),
            files = de.arjmandi.nanobot.companion.platform.InMemoryFileStore(),
            appInfo = de.arjmandi.nanobot.companion.platform.FakeAppInfo(platform = "contract"),
            dispatchers = de.arjmandi.nanobot.companion.platform.CoreDispatchers(
                compute = Dispatchers.Default,
                main = Dispatchers.Default,
            ),
        ),
        http = io.ktor.client.HttpClient(io.ktor.client.engine.java.Java) {
            install(io.ktor.client.plugins.websocket.WebSockets)
        },
    )

    private suspend fun <T> onRealDispatchers(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    /** Wait until this handle has [count] finished turns: a prompt sent mid-turn steers it. */
    private suspend fun ChatHandle.awaitTurns(count: Int): ThreadState =
        thread.first { state -> state.turns.count { it.status == TurnStatus.Complete } >= count }

    /**
     * Each client has its own socket, its own bootstrap and its own chat. The prompts are
     * unique strings, so cross-talk is not a matter of opinion: a prompt that appears in the
     * other client's thread is a routing bug.
     */
    @Test
    fun twoClientsInterleavedSendsNoCrossTalk() = runTest(timeout = 10.minutes) {
        assumeTrue("contract tests run with -Pcontract", contract)
        onRealDispatchers {
            val first = core()
            val second = core()
            assertIs<ConnectResult.Connected>(first.connect())
            assertIs<ConnectResult.Connected>(second.connect())
            val a = first.chat(null)
            val b = second.chat(null)

            val chats = listOf(a.id.value, b.id.value)
            assertTrue(chats.all { it != null }, "each greeting minted its own chat")
            assertEquals(2, chats.distinct().size, "two clients on one gateway are two chats, not one")

            val promptsA = listOf("alpha-one", "alpha-two")
            val promptsB = listOf("beta-one", "beta-two")

            // Interleaved across clients, one turn at a time each: a second send while a turn is
            // running is *steering* (C5.5), not a second turn, so the pairs take turns settling.
            for (round in promptsA.indices) {
                assertIs<SendReceipt.Accepted>(a.send(OutboundDraft(text = promptsA[round])), "A:$round")
                assertIs<SendReceipt.Accepted>(b.send(OutboundDraft(text = promptsB[round])), "B:$round")
                val settledA = a.awaitTurns(round + 1)
                val settledB = b.awaitTurns(round + 1)
                assertTrue(settledA.turns.last().status == TurnStatus.Complete, "A settled round $round")
                assertTrue(settledB.turns.last().status == TurnStatus.Complete, "B settled round $round")
            }

            val textA = a.thread.value.turns.mapNotNull { it.prompt?.text }
            val textB = b.thread.value.turns.mapNotNull { it.prompt?.text }

            assertEquals(promptsA, textA, "client A's own prompts, in order")
            assertEquals(promptsB, textB, "client B's own prompts, in order")
            assertTrue(textA.none { it in promptsB }, "no prompt of B's reached A")
            assertTrue(textB.none { it in promptsA }, "no prompt of A's reached B")
            assertTrue(
                a.thread.value.turns.none { turn -> turn.prompt?.text in promptsB },
                "no frame of B's was reduced into A's thread",
            )
            assertTrue(
                b.thread.value.turns.none { turn -> turn.prompt?.text in promptsA },
                "and none of A's into B's",
            )

            first.disconnect(forget = false)
            second.disconnect(forget = false)
            assertEquals(0, first.activeChildJobs(), "A left no job behind")
            assertEquals(0, second.activeChildJobs(), "B left no job behind")
        }
    }

    /**
     * `webui_request` → `webui_response` against the real gateway (C7.2). The dev gateway's
     * automations are all `system_event`s, so the honest live assertion is the refusal: the
     * client sends the mutation on the socket and keeps the gateway's 403 and its words.
     */
    @Test
    fun automationActionRoundTripsOverTheLiveSocket() = runTest(timeout = 5.minutes) {
        assumeTrue("contract tests run with -Pcontract", contract)
        onRealDispatchers {
            val core = core()
            assertIs<ConnectResult.Connected>(core.connect())
            assertEquals(null, core.refreshAutomations(), "the list is a plain read")
            val jobs = core.automations.value.jobs
            assertTrue(jobs.isNotEmpty(), "the dev gateway always runs heartbeat and dream")

            val protected = jobs.first { it.protected }
            val refused = assertIs<AutomationActionResult.Refused>(
                core.runAutomation(protected.id, AutomationActionKind.Run),
                "a system automation cannot be run; the gateway answers 403",
            )
            assertEquals(403, refused.status)
            assertEquals("system automation is protected", refused.message)

            core.disconnect(forget = false)
        }
    }

    /**
     * The parity claim, end to end: what the socket delivered and what the WebUI's thread page
     * holds reduce to the same thread (plan.md D9). `core-cli watch --verify` prints this as a
     * diff; here it is an assertion about the same two feeds.
     */
    @Test
    fun liveTurnAndTheWebuiThreadPageAgree() = runTest(timeout = 10.minutes) {
        assumeTrue("contract tests run with -Pcontract", contract)
        onRealDispatchers {
            val core = core()
            val url = profile["url"]!!.jsonPrimitive.content
            val secret = profile["secret"]!!.jsonPrimitive.content
            val http = io.ktor.client.HttpClient(io.ktor.client.engine.java.Java) {
                install(io.ktor.client.plugins.websocket.WebSockets)
            }
            try {
                assertIs<ConnectResult.Connected>(core.connect())
                val handle = core.chat(null)
                handle.setDraft("Reply with exactly the word: pong")
                assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "Reply with exactly the word: pong")))
                val live = handle.thread.first { state ->
                    state.activeTurnId == null && state.turns.any { it.status == TurnStatus.Complete }
                }

                val token = Json.parseToJsonElement(
                    http.get("$url/webui/bootstrap") { header("X-Nanobot-Auth", secret) }.bodyAsText(),
                ).jsonObject["api_token"]!!.jsonPrimitive.content
                val key = "websocket:${handle.id.value}"
                val page = http.get("$url/api/sessions/${key.replace(":", "%3A")}/webui-thread") {
                    header("Authorization", "Bearer $token")
                }.bodyAsText()

                val replayed = HistoryReplay.fromPage(page, chatId = handle.id.value.orEmpty())

                assertEquals(
                    live.turns.filter { it.status != TurnStatus.Running }.map { it.answer.text },
                    replayed.turns.filter { it.status != TurnStatus.Running }.map { it.answer.text },
                    "the answer text is the same from both feeds",
                )
                assertEquals(
                    live.turns.mapNotNull { it.prompt?.text },
                    replayed.turns.mapNotNull { it.prompt?.text },
                    "the prompt text is the same from both feeds",
                )
                assertEquals(
                    live.turns.map { it.status },
                    replayed.turns.map { it.status },
                    "and each turn settled the same way",
                )
                assertEquals(
                    live.turns.last().activity.map { (it as? de.arjmandi.nanobot.companion.projection.ActivityItem.Tool)?.name },
                    replayed.turns.last().activity.map { (it as? de.arjmandi.nanobot.companion.projection.ActivityItem.Tool)?.name },
                    "and the tool rows are the same rows",
                )
                core.disconnect(forget = false)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun theRecordedAutomationFixtureMatchesTheLiveList() = runTest(timeout = 5.minutes) {
        assumeTrue("contract tests run with -Pcontract", contract)
        onRealDispatchers {
            val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "core/src/commonTest/resources/fixtures") }
                .firstOrNull { it.isDirectory }
                ?.listFiles().orEmpty()
                .filter { it.isDirectory }
                .maxByOrNull { it.name }
                ?: error("no fixture set")
            val recorded = AutomationsParser.parse(File(file, "automations.json").readText())
            val core = core()
            assertIs<ConnectResult.Connected>(core.connect())
            core.refreshAutomations()
            val live = core.automations.value.jobs.map { it.id }.sorted()
            assertEquals(recorded.map { it.id }.sorted(), live, "the fixture still describes this gateway")
            core.disconnect(forget = false)
        }
    }
}
