package de.arjmandi.nanobot.companion.cli.record

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `core-cli record`: bootstrap, one scripted turn, then the two reads the app needs
 * for the same chat. The written set is the evidence `:core`'s codec is tested
 * against (plan.md C2.5, §3.3).
 */
internal class Recorder(
    private val profile: GatewayProfile,
    private val outRoot: File,
    private val prompt: String,
    private val gatewayCommit: String,
    private val homeDir: String,
) {

    fun run(): File = runBlocking {
        val client = GatewayClient()
        try {
            val rawBootstrap = client.bootstrap(profile)
            val apiToken = rawBootstrap["api_token"]?.jsonText()
                ?: error("bootstrap has no api_token; /api/sessions needs it (docs/decisions.md D15.6)")

            val frames = mutableListOf<JsonObject>()
            lateinit var chatId: String
            client.turn(
                profile = profile,
                bootstrap = rawBootstrap,
                turnId = TURN_ID,
                prompt = prompt,
                timeoutMs = TIMEOUT_MS,
                onFrame = { frame ->
                    frames += frame
                    if (frame["event"]?.jsonText() == "ready") chatId = frame["chat_id"]!!.jsonText()
                },
            )

            val sessionKey = "websocket:$chatId"
            val rawSessions = client.sessions(profile, apiToken)
            val rawHistory = client.thread(profile, apiToken, sessionKey)

            val redactor = Redactor(
                homeDir = homeDir,
                workspaceDir = workspaceDirIn(rawSessions, sessionKey).orEmpty(),
            )
            write(redactor, rawBootstrap, frames, rawSessions, rawHistory, chatId)
        } finally {
            client.close()
        }
    }

    private fun write(
        redactor: Redactor,
        rawBootstrap: JsonObject,
        rawFrames: List<JsonObject>,
        rawSessions: JsonObject,
        rawHistory: JsonObject,
        chatId: String,
    ): File {
        val webui = rawBootstrap["terminal"]?.let { (it as JsonObject)["webui"] as JsonObject }
            ?: error("bootstrap has no terminal.webui contract (D15.1)")
        val recordedAt = LocalDateTime.now(ZoneOffset.UTC)
        val set = File(outRoot, "${recordedAt.format(DATE)}-$gatewayCommit")
        check(set.parentFile == outRoot) { "refusing to replace $set outside $outRoot" }
        if (set.exists()) set.deleteRecursively()
        File(set, "ws").mkdirs()

        val written = LinkedHashMap<String, JsonObject>()
        written["bootstrap.json"] = redactor.redact(rawBootstrap) as JsonObject
        rawFrames.forEachIndexed { index, frame ->
            val name = frame["event"]?.jsonText() ?: "unlabelled"
            written["ws/${index.toString().padStart(2, '0')}-$name.json"] = redactor.redact(frame) as JsonObject
        }
        written["sessions.json"] = redactor.redact(rawSessions) as JsonObject
        written["history.json"] = redactor.redact(rawHistory) as JsonObject

        for ((path, document) in written) {
            val file = File(set, path)
            file.parentFile.mkdirs()
            file.writeText(document.toString())
        }

        val manifest = buildJsonObject {
            put("provenance", buildJsonObject {
                put("recorded_at", recordedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z")
                put("gateway_version", webui["version"]!!.jsonText())
                put("gateway_commit", gatewayCommit)
                put("gateway_url", profile.url)
                put("model", rawBootstrap["model_name"]!!.jsonText())
                put("protocol", buildJsonObject {
                    put("min", (webui["min_protocol"] as JsonPrimitive).content.toInt())
                    put("max", (webui["max_protocol"] as JsonPrimitive).content.toInt())
                    put("client", "webui.core.v1")
                })
                put("recorder", "core-cli record")
            })
            put("redaction", buildJsonObject {
                put("rules", JsonArray(redactor.rules.map(::JsonPrimitive)))
            })
            put("session", buildJsonObject {
                put("chat_id", (redactor.redact(JsonPrimitive(chatId)) as JsonPrimitive).content)
                put("session_key", (redactor.redact(JsonPrimitive("websocket:$chatId")) as JsonPrimitive).content)
                put("turn_id", TURN_ID)
                put("prompt", prompt)
                put(
                    "outbound",
                    buildJsonArray {
                        // Redacted like any frame, so the recorded script carries the
                        // same chat alias as the frames it produced.
                        add(redactor.redact(Json.parseToJsonElement(GatewayClient.messageEnvelope(chatId, TURN_ID, prompt))))
                    },
                )
            })
            put(
                "events",
                buildJsonArray { rawFrames.forEach { add(JsonPrimitive(it["event"]!!.jsonText())) } },
            )
            put(
                "files",
                buildJsonArray {
                    written.keys.forEach { path ->
                        add(buildJsonObject {
                            put("path", path)
                            put("bytes", File(set, path).length())
                        })
                    }
                },
            )
        }
        File(set, "manifest.json").writeText(manifest.toString())
        return set
    }

    /** The recording's own workspace, so its absolute path is redacted to <workspace>. */
    private fun workspaceDirIn(sessions: JsonObject, sessionKey: String): String? =
        (sessions["sessions"] as? JsonArray)
            ?.map { it as JsonObject }
            ?.firstOrNull { it["key"]?.jsonText() == sessionKey }
            ?.get("workspace_scope")
            ?.let { (it as? JsonObject)?.get("project_path")?.jsonText() }

    private companion object {
        const val TURN_ID: String = "turn-1"
        const val TIMEOUT_MS: Long = 240_000
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

internal fun JsonElement.jsonText(): String = (this as JsonPrimitive).content
