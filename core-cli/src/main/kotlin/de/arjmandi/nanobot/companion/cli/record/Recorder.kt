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
 * `core-cli record`: bootstrap, one scripted scenario at a time, then the reads the app
 * needs for the chats those scenarios created. The written set is the evidence `:core`'s
 * codec and reducer are tested against (plan.md C2.5, §3.3, and P3's scenarios).
 *
 * A scenario gets its own bootstrap, because a bootstrap token is single-use per
 * handshake (plan.md §0). Its frames land in its own directory and the manifest is the
 * index: which scenario, which envelope, which chat, which events.
 */
internal class Recorder(
    private val profile: GatewayProfile,
    private val outRoot: File,
    private val scenarios: List<Scenario>,
    private val gatewayCommit: String,
    private val homeDir: String,
) {

    private class Recorded(
        val scenario: Scenario,
        val bootstrap: JsonObject,
        val chatId: String,
        val frames: List<JsonObject>,
        val history: JsonObject?,
        val historyPath: String?,
    )

    fun run(): File = runBlocking {
        val client = GatewayClient()
        try {
            // One throwaway bootstrap for the api token the HTTP reads need (D15.6).
            val apiToken = client.bootstrap(profile)["api_token"]?.jsonText()
                ?: error("bootstrap has no api_token; /api/sessions needs it (docs/decisions.md D15.6)")

            // The file scenario writes a fixed path, so the previous run's copy has to go or
            // the write changes nothing and the frame carries no diff. The recorder owns
            // this file: it is fixture plumbing, not the agent's workspace contents.
            if (scenarios.any { it.name == Scenarios.FILE_TARGET_SCENARIO }) {
                workspaceDirOf(client.sessions(profile, apiToken))
                    ?.let { dir -> File(dir, Scenarios.FILE_TARGET).delete() }
            }

            val recorded = scenarios.map { scenario ->
                val bootstrap = client.bootstrap(profile)
                val frames = mutableListOf<JsonObject>()
                var chatId = ""
                client.turn(profile, bootstrap, scenario, timeoutMs = TIMEOUT_MS) { frame ->
                    frames += frame
                    if (frame["event"]?.jsonText() == "ready") chatId = frame["chat_id"]!!.jsonText()
                }
                check(frames.isNotEmpty()) { "scenario ${scenario.name} recorded no frames" }
                check(chatId.isNotEmpty()) { "scenario ${scenario.name} recorded no ready frame" }
                Recorded(
                    scenario = scenario,
                    bootstrap = bootstrap,
                    chatId = chatId,
                    frames = frames,
                    // The error scenario rejects a made-up chat id, so there is no thread.
                    history = if (scenario.recordsHistory) {
                        client.thread(profile, apiToken, "websocket:$chatId")
                    } else {
                        null
                    },
                    // The plain turn keeps the root history.json the replay test reads.
                    historyPath = if (!scenario.recordsHistory) {
                        null
                    } else if (scenario.framesDir == "ws") {
                        "history.json"
                    } else {
                        "${scenario.framesDir}/history.json"
                    },
                )
            }

            val rawSessions = client.sessions(profile, apiToken)
            val paletteReads = reads(client, apiToken)
            val plainChat = recorded.first { it.scenario.name == "plain" }.chatId
            val redactor = Redactor(
                homeDir = homeDir,
                workspaceDir = workspaceDirIn(rawSessions, "websocket:$plainChat").orEmpty(),
                gatewayUrl = profile.url,
            )
            write(redactor, recorded, rawSessions, paletteReads)
        } finally {
            client.close()
        }
    }

    /**
     * The HTTP reads no scripted turn produces: the command palette and the installed CLI
     * apps (C6.4). They are plain reads, so they are captured into a set instead of costing
     * a model turn each.
     */
    fun captureReads(): File = runBlocking {
        val client = GatewayClient()
        try {
            val set = newestSet()
            val apiToken = client.bootstrap(profile)["api_token"]?.jsonText()
                ?: error("bootstrap has no api_token; the reads need one (docs/decisions.md D15.6)")
            val rawSessions = client.sessions(profile, apiToken)
            val redactor = Redactor(
                homeDir = homeDir,
                workspaceDir = workspaceDirOf(rawSessions).orEmpty(),
                gatewayUrl = profile.url,
            )
            val reads = reads(client, apiToken)
            writeReads(set, redactor, reads)
            rewriteManifest(set, redactor, reads)
            set
        } finally {
            client.close()
        }
    }

    /**
     * The read endpoints the app needs that no turn produces: the two palettes (C6.4), the
     * settings overview and its usage totals (C7.1), and the automation jobs (C7.2).
     */
    private suspend fun reads(client: GatewayClient, apiToken: String): Map<String, JsonObject> = linkedMapOf(
        "palette/commands.json" to client.commands(profile, apiToken),
        "palette/cli-apps.json" to client.installedCliApps(profile, apiToken),
        "settings.json" to client.settings(profile, apiToken),
        "settings-usage.json" to client.settingsUsage(profile, apiToken),
        "automations.json" to client.automations(profile, apiToken),
    )

    private fun writeReads(set: File, redactor: Redactor, reads: Map<String, JsonObject>) {
        for ((path, document) in reads) {
            val file = File(set, path)
            file.parentFile.mkdirs()
            file.writeText((redactor.redact(document) as JsonObject).toString())
        }
    }

    /**
     * A set's manifest indexes its files, so a capture after a recording rebuilds that index
     * from the directory rather than leaving the new documents unlisted (the manifest test
     * compares the two lists).
     */
    private fun rewriteManifest(set: File, redactor: Redactor, reads: Map<String, JsonObject>) {
        val manifestFile = File(set, "manifest.json")
        val existing = Json.parseToJsonElement(manifestFile.readText()) as JsonObject
        val listed = buildJsonArray {
            set.walkTopDown()
                .filter { it.isFile && it.name != "manifest.json" }
                .sortedBy { it.relativeTo(set).path }
                .forEach { file ->
                    add(buildJsonObject {
                        put("path", file.relativeTo(set).path)
                        put("bytes", file.length())
                    })
                }
        }
        // A read captured with a rule the original recording did not use still has to be
        // documented where review looks for redaction: the set-level rule list.
        val knownRules = (existing["redaction"]?.let { (it as? JsonObject)?.get("rules") } as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        val updated = JsonObject(
            existing + mapOf(
                "redaction" to buildJsonObject {
                    put("rules", JsonArray((knownRules + redactor.rules).distinct().map(::JsonPrimitive)))
                },
                "files" to listed,
                "reads" to buildJsonObject {
                    put("recorded_at", LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z")
                    put("endpoints", buildJsonArray { reads.keys.forEach { add(JsonPrimitive(it)) } })
                    put("rules", JsonArray(redactor.rules.map(::JsonPrimitive)))
                },
            ),
        )
        manifestFile.writeText(updated.toString())
    }

    /** The newest recording is the set a read-only capture belongs to. */
    private fun newestSet(): File =
        outRoot.listFiles().orEmpty().filter { it.isDirectory && File(it, "manifest.json").isFile }
            .maxByOrNull { it.name }
            ?: error("no fixture set under $outRoot; run `core-cli record` first")

    private fun write(
        redactor: Redactor,
        recorded: List<Recorded>,
        rawSessions: JsonObject,
        paletteReads: Map<String, JsonObject>,
    ): File {
        val plain = recorded.first { it.scenario.name == "plain" }
        val webui = plain.bootstrap["terminal"]?.let { (it as JsonObject)["webui"] as JsonObject }
            ?: error("bootstrap has no terminal.webui contract (D15.1)")
        val recordedAt = LocalDateTime.now(ZoneOffset.UTC)
        val set = File(outRoot, "${recordedAt.format(DATE)}-$gatewayCommit")
        check(set.parentFile == outRoot) { "refusing to replace $set outside $outRoot" }
        if (set.exists()) set.deleteRecursively()
        File(set, "ws").mkdirs()

        val written = LinkedHashMap<String, JsonObject>()
        written["bootstrap.json"] = redactor.redact(plain.bootstrap) as JsonObject
        for (turn in recorded) {
            turn.frames.forEachIndexed { index, frame ->
                val name = frame["event"]?.jsonText() ?: "unlabelled"
                written["${turn.scenario.framesDir}/${index.toString().padStart(2, '0')}-$name.json"] =
                    redactor.redact(frame) as JsonObject
            }
            turn.historyPath?.let { path ->
                written[path] = redactor.redact(turn.history!!) as JsonObject
            }
        }
        written["sessions.json"] = redactor.redact(rawSessions) as JsonObject
        for ((path, document) in paletteReads) {
            written[path] = redactor.redact(document) as JsonObject
        }

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
                // Not `profile.url` (T55): a fixture is committed, and the address a person
                // records from is theirs. The host still reaches the manifests that mention it,
                // where the redactor replaces it.
                put("gateway_url", EXAMPLE_GATEWAY_URL)
                put("model", plain.bootstrap["model_name"]!!.jsonText())
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
            put("session", sessionOf(redactor, plain))
            put("scenarios", buildJsonArray { recorded.forEach { add(scenarioManifest(redactor, it)) } })
            // D15.2: the plain turn's event order, at the root because it is the sequence
            // the codec and reducer tests are written against.
            put("events", buildJsonArray { plain.frames.forEach { add(JsonPrimitive(it["event"]!!.jsonText())) } })
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

    private fun sessionOf(redactor: Redactor, plain: Recorded): JsonObject = buildJsonObject {
        put("chat_id", redacted(redactor, plain.chatId))
        put("session_key", redacted(redactor, "websocket:${plain.chatId}"))
        put("turn_id", plain.scenario.turnIdOf(0))
        put("prompt", plain.scenario.prompt)
        put("outbound", outboundOf(redactor, plain))
    }

    private fun scenarioManifest(redactor: Redactor, turn: Recorded): JsonObject = buildJsonObject {
        put("name", turn.scenario.name)
        put("frames", turn.scenario.framesDir)
        put("prompt", turn.scenario.prompt)
        put("chat_id", redacted(redactor, turn.chatId))
        put("turn_id", turn.scenario.turnIdOf(0))
        put("settles", turn.scenario.settles)
        put("history", turn.historyPath ?: "")
        put("steps", outboundOf(redactor, turn))
        put("events", buildJsonArray { turn.frames.forEach { add(JsonPrimitive(it["event"]!!.jsonText())) } })
    }

    /** Every envelope the scenario sent, redacted like the frames they produced. */
    private fun outboundOf(redactor: Redactor, turn: Recorded): JsonArray = buildJsonArray {
        turn.scenario.steps.forEachIndexed { index, step ->
            add(buildJsonObject {
                put("after_settles", step.afterSettles)
                put("turn_id", turn.scenario.turnIdOf(index))
                val envelope = step.frame(turn.chatId, turn.scenario.turnIdOf(index))
                put("frame", redactor.redact(Json.parseToJsonElement(envelope)))
            })
        }
    }

    /** The same alias the frames carry, so a manifest chat id means one chat in the set. */
    private fun redacted(redactor: Redactor, value: String): String =
        (redactor.redact(JsonPrimitive(value)) as JsonPrimitive).content

    /** Any session row names the workspace: they all run in the gateway's one workspace. */
    private fun workspaceDirOf(sessions: JsonObject): String? =
        (sessions["sessions"] as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.get("workspace_scope") }
            ?.let { (it as? JsonObject)?.get("project_path")?.jsonText() }

    /** The recording's own workspace, so its absolute path is redacted to <workspace>. */
    private fun workspaceDirIn(sessions: JsonObject, sessionKey: String): String? =
        (sessions["sessions"] as? JsonArray)
            ?.map { it as JsonObject }
            ?.firstOrNull { it["key"]?.jsonText() == sessionKey }
            ?.get("workspace_scope")
            ?.let { (it as? JsonObject)?.get("project_path")?.jsonText() }

    private companion object {
        const val TIMEOUT_MS: Long = 240_000
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

internal fun JsonElement.jsonText(): String = (this as JsonPrimitive).content
