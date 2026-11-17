package de.arjmandi.nanobot.companion.contract

import de.arjmandi.nanobot.companion.facade.ConnectResult
import de.arjmandi.nanobot.companion.facade.ConnectionFailure
import de.arjmandi.nanobot.companion.facade.ConnectionState
import de.arjmandi.nanobot.companion.facade.GatewayProfile
import de.arjmandi.nanobot.companion.facade.NanobotCore
import de.arjmandi.nanobot.companion.facade.OutboundDraft
import de.arjmandi.nanobot.companion.facade.SendReceipt
import de.arjmandi.nanobot.companion.facade.connectionFailure
import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.platform.systemClock
import de.arjmandi.nanobot.companion.projection.TurnStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

/**
 * The wire against a real gateway. These run only with `-Pcontract` and a
 * gateway started by `tools/nanobot-dev.sh`, and they are the only tests in the repo that are
 * allowed to be slow: one real turn through a real model, the drawer's real payload, and a real
 * refusal.
 *
 * The secret is read from `.dev/profile.json` and never printed.
 */
class LiveGatewayContractTest {

    private val contract: Boolean = System.getProperty("nanobot.contract") == "true"

    private fun profile(): GatewayProfile {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, ".dev/profile.json") }
            .firstOrNull { it.isFile }
            ?: error(".dev/profile.json not found; run tools/nanobot-dev.sh start")
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        return GatewayProfile(
            url = json["url"]!!.jsonPrimitive.content,
            secret = json["secret"]!!.jsonPrimitive.content,
        )
    }

    private fun deps(): CoreDeps = CoreDeps(
        clock = systemClock(),
        // Nothing here touches the platform stores: a contract run must not leave a key behind.
        secureStore = InMemorySecureStore(),
        files = InMemoryFileStore(),
        appInfo = FakeAppInfo(platform = "contract"),
        dispatchers = CoreDispatchers(compute = Dispatchers.Default, main = Dispatchers.Default),
    )

    private fun http(): HttpClient = HttpClient(Java) { install(WebSockets) }

    private fun core(): NanobotCore = NanobotCore(profile(), deps(), http())

    /**
     * Everything below runs on a real dispatcher: `runTest`'s virtual clock would make the
     * engine's own ready and transcription timeouts fire the moment the test looked away, and a
     * contract test is the one place where real seconds are the point.
     */
    private suspend fun <T> onRealDispatchers(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    @Test
    fun bootstrapsAgainstTheLiveGateway() = runTest(timeout = 2.minutes) {
        assumeTrue("contract tests run with -Pcontract against tools/nanobot-dev.sh", contract)
        onRealDispatchers {
        val core = core()

        val connected = core.connect()

        assertIs<ConnectResult.Connected>(connected, "bootstrap + handshake + ready")
        assertEquals(ConnectionState.Open, core.connection.value)
        core.disconnect(forget = false)
        assertEquals(0, core.activeChildJobs())
        }
    }

    @Test
    fun listsTheGatewaysSessions() = runTest(timeout = 2.minutes) {
        assumeTrue("contract tests run with -Pcontract", contract)
        onRealDispatchers {
        val core = core()
        assertIs<ConnectResult.Connected>(core.connect())

        val loaded = core.sessions.first { it.loaded }

        assertTrue(loaded.rows.isNotEmpty(), "the dev gateway has recorded sessions; the drawer needs rows")
        assertTrue(loaded.rows.all { it.key.startsWith("websocket:") }, "rows are keyed websocket:<uuid> (D15.6)")
        assertTrue(loaded.rows.all { it.updatedAtMs != null }, "ISO timestamps parse (D15.2)")
        core.disconnect(forget = false)
        }
    }

    @Test
    fun drivesOneRealTurn() = runTest(timeout = 10.minutes) {
        assumeTrue("contract tests run with -Pcontract", contract)
        onRealDispatchers {
        val core = core()
        assertIs<ConnectResult.Connected>(core.connect())
        val handle = core.chat(null)

        val receipt = assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "Reply with exactly the word: pong")))

        val settled = handle.thread.first { thread ->
            thread.turns.any { it.id == receipt.turnId && it.status != TurnStatus.Running }
        }
        val turn = settled.turn(receipt.turnId)!!

        assertEquals(TurnStatus.Complete, turn.status, "a real turn ends complete, not failed")
        assertTrue(turn.answer.text.isNotBlank(), "the answer streamed through the facade: '${turn.answer.text.take(40)}'")
        assertTrue(turn.answer.segments.any { it.closed }, "stream_end closed the segment")
        assertTrue(settled.usage != null || turn.usage != null, "turn_end carried usage (D15.4)")
        core.disconnect(forget = false)
        }
    }

    @Test
    fun openingAStoredChatReplaysItsHistoryThroughTheFacade() = runTest(timeout = 5.minutes) {
        assumeTrue("contract tests run with -Pcontract", contract)
        onRealDispatchers {
        val core = core()
        assertIs<ConnectResult.Connected>(core.connect())
        val listed = core.sessions.first { it.loaded }
        val stored = listed.rows.firstOrNull { it.preview.isNotBlank() }
            ?: error("the dev gateway has no session with history; run one turn first")

        // A chat this socket never watched: everything that lands here came from the page read,
        // which is exactly what the app was missing after a reconnect.
        val handle = core.chat(stored.chatId)
        handle.open()

        val replayed = handle.thread.first { it.turns.isNotEmpty() }
        assertTrue(
            replayed.turns.all { it.prompt?.text?.isNotBlank() == true },
            "every replayed turn has its stored prompt",
        )
        assertTrue(
            replayed.turns.any { it.answer.text.isNotBlank() },
            "and at least one stored answer came back: ${replayed.turns.map { it.answer.text.take(20) }}",
        )
        core.disconnect(forget = false)
        }
    }

    @Test
    fun refusesAWrongSecret() = runTest(timeout = 2.minutes) {
        assumeTrue("contract tests run with -Pcontract", contract)
        val real = profile()
        onRealDispatchers {
        val core = NanobotCore(real.copy(secret = "definitely-not-the-secret"), deps(), http())

        val result = assertIs<ConnectResult.Failed>(core.connect())

        assertEquals(ConnectionFailure.AuthFailed, result.error.connectionFailure().failure, "a 401 is AuthFailed, never a broken chat")
        assertEquals(ConnectionState.Failed(ConnectionFailure.AuthFailed), core.connection.value)
        assertEquals(0, core.activeChildJobs(), "a refused connect leaves no pump behind")
        }
    }
}
