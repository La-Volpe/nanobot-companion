package de.arjmandi.nanobot.companion.contract

import de.arjmandi.nanobot.companion.facade.ConnectResult
import de.arjmandi.nanobot.companion.facade.ConnectionState
import de.arjmandi.nanobot.companion.facade.GatewayProfile
import de.arjmandi.nanobot.companion.facade.NanobotCore
import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.platform.systemClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

/**
 * The heartbeat against a gateway that dies without a close (plan.md §2). `SIGSTOP` is the
 * cleanest silent death a test can produce: the process is frozen, so the kernel keeps both
 * ends of the connection open, no FIN and no close frame go anywhere, and the socket is
 * exactly what a phone that changed networks comes back to. Only the heartbeat can notice.
 *
 * Runs only with `-Pcontract`; it pauses the dev gateway for about half a minute and resumes
 * it, so nothing else may be talking to it at the time.
 */
class LiveHeartbeatContractTest {

    private val contract: Boolean = System.getProperty("nanobot.contract") == "true"

    private fun devFile(name: String): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, ".dev/$name") }
            .firstOrNull { it.isFile }
            ?: error(".dev/$name not found; run tools/nanobot-dev.sh start")

    private fun profile(): GatewayProfile {
        val json = Json.parseToJsonElement(devFile("profile.json").readText()).jsonObject
        return GatewayProfile(
            url = json["url"]!!.jsonPrimitive.content,
            secret = json["secret"]!!.jsonPrimitive.content,
        )
    }

    private fun deps(): CoreDeps = CoreDeps(
        clock = systemClock(),
        secureStore = InMemorySecureStore(),
        files = InMemoryFileStore(),
        appInfo = FakeAppInfo(platform = "contract"),
        dispatchers = CoreDispatchers(compute = Dispatchers.Default, main = Dispatchers.Default),
    )

    /**
     * `nanobot webui` forks the process that actually listens (`nanobot-gateway`), so the pid
     * in `.dev/gateway.pid` may have no socket of its own: every descendant is frozen, and the
     * pid itself last.
     */
    private fun pids(root: String): List<String> {
        val children = ProcessBuilder("pgrep", "-P", root).start().inputStream.bufferedReader()
            .readLines().map(String::trim).filter { it.isNotEmpty() }
        return children + root
    }

    private fun signal(root: String, name: String) {
        for (pid in pids(root)) ProcessBuilder("kill", "-$name", pid).start().waitFor()
    }

    @Test
    fun aGatewayThatFreezesWithoutACloseIsNoticed() = runTest(timeout = 3.seconds * 90) {
        assumeTrue("contract tests run with -Pcontract against tools/nanobot-dev.sh", contract)
        val pid = devFile("gateway.pid").readText().trim()
        val core = NanobotCore(profile(), deps(), HttpClient(Java) { install(WebSockets) })
        try {
            withContext(Dispatchers.Default) {
                assertIs<ConnectResult.Connected>(core.connect(), "bootstrap + handshake + ready")
                core.setForeground(true)
                delay(1_000)

                // The gateway stops answering: no FIN, no close frame, nothing for the pump.
                signal(pid, "STOP")
                val flipped = withTimeoutOrNull(60.seconds) {
                    core.connection.first { state -> state !is ConnectionState.Open }
                }
                signal(pid, "CONT")

                assertIs<ConnectionState.Failed>(
                    flipped,
                    "a socket that answers nothing must not keep reporting Open",
                )
            }
        } finally {
            // Whatever happened, the gateway comes back and the socket is closed here.
            signal(pid, "CONT")
            core.disconnect(forget = false)
        }
        assertEquals(ConnectionState.Idle, core.connection.value, "the client closed its own socket")
    }
}
