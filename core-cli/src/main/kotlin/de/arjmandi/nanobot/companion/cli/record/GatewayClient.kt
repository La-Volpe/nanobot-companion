package de.arjmandi.nanobot.companion.cli.record

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `{url, secret}` as tools/nanobot-dev.sh writes it into `.dev/profile.json`. */
internal data class GatewayProfile(val url: String, val secret: String) {

    companion object {
        fun read(text: String): GatewayProfile {
            val json = Json.parseToJsonElement(text).jsonObject
            return GatewayProfile(
                url = json["url"]!!.jsonPrimitive.content.trimEnd('/'),
                secret = json["secret"]!!.jsonPrimitive.content,
            )
        }
    }
}

/**
 * The recorder talks to the gateway itself rather than through `:core`, because the
 * fixtures are what `:core`'s codec is written from (plan.md C2.5 before C2.1).
 * Every call here mirrors a WebUI call, and the responses are written verbatim
 * apart from [Redactor].
 */
internal class GatewayClient : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = HttpClient(Java) { install(WebSockets) }

    suspend fun bootstrap(profile: GatewayProfile): JsonObject =
        get(profile.url + "/webui/bootstrap", profile.secret)

    suspend fun sessions(profile: GatewayProfile, apiToken: String): JsonObject =
        get(profile.url + "/api/sessions", apiToken)

    suspend fun thread(profile: GatewayProfile, apiToken: String, sessionKey: String): JsonObject =
        get(profile.url + "/api/sessions/" + urlEncode(sessionKey) + "/webui-thread", apiToken)

    /**
     * One scripted turn: the socket's greeting decides the chat id, then a single
     * `message` envelope is sent and every frame is handed to [onFrame] until the
     * gateway settles the turn with `turn_end`.
     */
    suspend fun turn(
        profile: GatewayProfile,
        bootstrap: JsonObject,
        turnId: String,
        prompt: String,
        timeoutMs: Long,
        onFrame: (JsonObject) -> Unit,
    ) {
        val token = bootstrap["token"]!!.jsonPrimitive.content
        val wsUrl = bootstrap["ws_url"]!!.jsonPrimitive.content
        val separator = if (wsUrl.contains('?')) '&' else '?'

        val socket = http.webSocketSession("$wsUrl${separator}client_id=$CLIENT_ID&token=$token")
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    val frame = socket.incoming.receive()
                    if (frame !is Frame.Text) continue
                    val event = json.parseToJsonElement(frame.readText()).jsonObject
                    onFrame(event)
                    val name = event["event"]?.jsonPrimitive?.content
                    if (name == "ready") {
                        // The gateway mints the chat id in its greeting; the scripted
                        // turn rides that chat, not one the client invents.
                        val chatId = event["chat_id"]!!.jsonPrimitive.content
                        socket.send(Frame.Text(messageEnvelope(chatId, turnId, prompt)))
                    }
                    if (name == "turn_end" || name == "error") return@withTimeout
                }
            }
        } finally {
            socket.close()
        }
    }

    private suspend fun get(url: String, bearer: String): JsonObject {
        val response = http.get(url) { header(HttpHeaders.Authorization, "Bearer $bearer") }
        check(response.status.isSuccess()) { "GET $url -> ${response.status}" }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    override fun close(): Unit = http.close()

    internal companion object {
        // Distinct from the WebUI's own client id so a recording is identifiable in
        // the gateway log, and stable so nothing about it churns.
        const val CLIENT_ID: String = "nanobot-companion-recorder"

        /** The envelope the WebUI sends: typed, webui-flagged, with a client turn id. */
        fun messageEnvelope(chatId: String, turnId: String, prompt: String): String =
            """{"type":"message","chat_id":"$chatId","content":${JsonPrimitive(prompt)},""" +
                """"webui":true,"turn_id":"$turnId"}"""

        fun urlEncode(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}
