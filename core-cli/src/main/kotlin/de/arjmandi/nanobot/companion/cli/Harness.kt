package de.arjmandi.nanobot.companion.cli

import de.arjmandi.nanobot.companion.facade.ChatHandle
import de.arjmandi.nanobot.companion.facade.ConnectResult
import de.arjmandi.nanobot.companion.facade.GatewayProfile
import de.arjmandi.nanobot.companion.facade.NanobotCore
import de.arjmandi.nanobot.companion.platform.AppInfo
import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FileStore
import de.arjmandi.nanobot.companion.platform.SecureStore
import de.arjmandi.nanobot.companion.platform.systemClock
import de.arjmandi.nanobot.companion.projection.ThreadState
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The harness's own composition root. `:core`'s production deps are the two
 * apps' (`productionDeps` is per platform); the CLI needs no keystore and no app container,
 * so it passes the smallest true implementations and keeps its files under `.dev/cli-cache`.
 */
internal class Harness private constructor(
    val core: NanobotCore,
    val http: HttpClient,
    val profile: GatewayProfile,
) : AutoCloseable {

    companion object {

        fun open(profileFile: File): Harness {
            if (!profileFile.isFile) {
                throw HarnessError("no profile at $profileFile; start the gateway with tools/nanobot-dev.sh start")
            }
            val json = Json.parseToJsonElement(profileFile.readText()) as JsonObject
            val profile = GatewayProfile(
                url = json["url"]!!.jsonPrimitive.content.trimEnd('/'),
                secret = json["secret"]!!.jsonPrimitive.content,
            )
            val http = HttpClient(Java) { install(WebSockets) }
            val deps = CoreDeps(
                clock = systemClock(),
                secureStore = VolatileSecureStore(),
                files = DevFileStore(File(profileFile.parentFile, "cli-cache")),
                appInfo = CliAppInfo,
                dispatchers = CoreDispatchers(compute = Dispatchers.Default, main = Dispatchers.Default),
            )
            return Harness(NanobotCore(profile, deps, http), http, profile)
        }
    }

    /**
     * The bootstrap `api_token` the HTTP reads need. It is fetched here rather than
     * read out of the facade: the token belongs to a bootstrap, and a second bootstrap only
     * costs a token nobody handshakes with.
     */
    suspend fun apiToken(): String {
        val response = http.get("${profile.url}/webui/bootstrap") {
            header("X-Nanobot-Auth", profile.secret)
        }
        val document = Json.parseToJsonElement(response.bodyAsText()) as JsonObject
        return document["api_token"]?.jsonPrimitive?.content
            ?: throw HarnessError("the gateway's bootstrap has no api_token; /api/* reads need one (D15.6)")
    }

    /** The WebUI's own thread page for a chat, exactly as the browser reads it. */
    suspend fun thread(sessionKey: String): JsonObject {
        val encoded = java.net.URLEncoder.encode(sessionKey, Charsets.UTF_8).replace("+", "%20")
        val response = http.get("${profile.url}/api/sessions/$encoded/webui-thread") {
            header("Authorization", "Bearer ${apiToken()}")
        }
        if (response.status.value != 200) {
            throw HarnessError("GET webui-thread -> ${response.status.value}")
        }
        return Json.parseToJsonElement(response.bodyAsText()) as JsonObject
    }

    suspend fun disconnect() {
        core.disconnect(forget = false)
    }

    suspend fun connect(): ChatHandle {
        when (val result = core.connect()) {
            is ConnectResult.Failed -> throw HarnessError("connect failed: ${result.error}")
            is ConnectResult.Connected -> Unit
        }
        return core.chat(null)
    }

    /** The chat to work on: the one the greeting minted, or the one the caller named. */
    fun chat(chatId: String?): ChatHandle = if (chatId == null) core.chat(null) else core.chat(chatId)

    override fun close() {
        http.close()
    }
}

/** A harness failure the command prints and turns into exit code 1. */
internal class HarnessError(message: String) : Exception(message)

/** Nothing here persists a credential, so nothing here needs a keystore; that rule is about the apps. */
private class VolatileSecureStore : SecureStore {
    private val values = mutableMapOf<String, String>()
    override fun put(alias: String, value: String) {
        values[alias] = value
    }

    override fun get(alias: String): String? = values[alias]

    override fun remove(alias: String) {
        values.remove(alias)
    }
}

/** The CLI's cache lives beside the profile in `.dev/`, never in a user directory. */
private class DevFileStore(private val root: File) : FileStore {

    override fun write(name: String, text: String) {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun read(name: String): String? = File(root, name).takeIf { it.isFile }?.readText()

    override fun delete(name: String) {
        File(root, name).delete()
    }
}

private object CliAppInfo : AppInfo {
    override val version: String = "0.0.0"
    override val build: String = "cli"
    override val platform: String = "cli"
}

/** Wait for the chat to go quiet, with a ceiling so a stuck gateway fails the command. */
internal suspend fun ChatHandle.settled(timeoutMs: Long): ThreadState = withTimeout(timeoutMs) {
    thread.first { state ->
        val active = state.activeTurnId
        val last = state.turns.lastOrNull()
        active == null && last != null && last.status != de.arjmandi.nanobot.companion.projection.TurnStatus.Running
    }
}
