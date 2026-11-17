package de.arjmandi.nanobot.companion.cli.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fixtures are committed, so every value that changes between two recordings of the
 * same scripted turn has to be normalised, and every value that identifies this
 * machine or this login has to go.
 */
class RedactorTest {

    private val home = "/Users/someone"

    // A host that is not this project's own: the test proves a real-looking authority is replaced,
    // and the repository must not carry the owner's gateway again after the history scrub.
    private val gatewayUrl = "https://my-private-gateway.example:8787"

    private fun redact(vararg documents: String): List<JsonElement> {
        val redactor = Redactor(
            homeDir = home,
            workspaceDir = "$home/Dev/repo/.dev/workspace",
            gatewayUrl = gatewayUrl,
        )
        return documents.map { redactor.redact(Json.parseToJsonElement(it)) }
    }

    private fun JsonElement.at(vararg path: String): JsonPrimitive {
        var node: JsonElement = this
        for (step in path) node = node.jsonObject[step] ?: error("missing $step in $this")
        return node.jsonPrimitive
    }

    @Test
    fun replacesSecretsWithPlaceholder() {
        val out = redact("""{"token":"nbwt_abc123","api_token":"nbwt_def456","upload":{"token":"upl_9"}}""").single()

        assertEquals("REDACTED", out.at("token").content)
        assertEquals("REDACTED", out.at("api_token").content)
        assertEquals("REDACTED", out.at("upload", "token").content)
    }

    @Test
    fun replacesUuidsConsistentlyAcrossDocuments() {
        val chat = "290bed80-cb7e-405c-8d20-4900347b55fc"
        val other = "751db5ed-4a1c-4676-9c3e-065683a7ec6b"
        val out = redact(
            """{"chat_id":"$chat","request_id":"87d29ebb96354bc4912910cd41714c86"}""",
            """{"key":"websocket:$chat","sessionKey":"websocket:$other"}""",
        )

        val first = out[0].at("chat_id").content
        assertEquals(first, out[1].at("key").content.removePrefix("websocket:"), "the same chat keeps one alias")
        assertTrue(!first.contains(chat), "the real uuid must be gone")
        assertTrue(out[0].at("request_id").content != first, "a request id is not a chat id")
    }

    @Test
    fun normalisesIsoTimestampsAndKeepsTheirOrder() {
        val out = redact(
            """{"sessions":[
                {"created_at":"2026-11-14T03:23:21.667888","updated_at":"2026-11-14T03:23:22.075000"},
                {"created_at":"2026-11-13T02:22:01.106790","updated_at":"2026-11-14T03:22:53.780000"}
            ]}""",
        ).single()

        val sessions = (out.jsonObject["sessions"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        val all = sessions.flatMap { row -> listOf(row["created_at"]!!.jsonPrimitive.content, row["updated_at"]!!.jsonPrimitive.content) }
        assertTrue(all.all { Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?$""").matches(it) }, "still ISO-8601: $all")
        assertTrue(all.none { it.contains("2026") }, "the recording date is churn and must not survive: $all")
        // The drawer sorts sessions by updated_at, so normalisation must not collapse them.
        assertEquals(4, all.toSet().size, "distinct timestamps stay distinct and keep their rank: $all")
        assertTrue(
            sessions[0]["created_at"]!!.jsonPrimitive.content < sessions[0]["updated_at"]!!.jsonPrimitive.content,
            "order inside a row is preserved",
        )
    }

    @Test
    fun zeroesChurningTimings() {
        val out = redact(
            """{"started_at":1794623001.667754,"turn_end":{"latency_ms":404,"usage":{"ttft_ms":367,"generation_ms":20}},
                "created_at_ms":1794623001668,"stream_end":{"text_from_seq":3}}""",
        ).single()

        assertEquals("0", out.at("started_at").content)
        assertEquals("0", out.at("created_at_ms").content)
        assertEquals("0", out.at("turn_end", "latency_ms").content)
        assertEquals("0", out.at("turn_end", "usage", "ttft_ms").content)
        // Counters that do not identify anything or churn per run stay untouched.
        assertEquals("3", out.at("stream_end", "text_from_seq").content)
    }

    @Test
    fun replacesOpaqueStreamIdsAndHashes() {
        val out = redact(
            """{"stream_id":"websocket:290bed80-cb7e-405c-8d20-4900347b55fc:1794623001670042000:0",
                "handle":{"id":"handle_1b99a6c3ac1186ebe1e80dc51f5e546a"},
                "page":{"history_generation":"3f8b2b694f064d269914b88eb5a6262c"}}""",
        ).single()

        val stream = out.at("stream_id").content
        assertTrue(Regex("""^stream-\d+$""").matches(stream), "stream_id is opaque, so an alias keeps its purpose: $stream")
        assertTrue(!out.at("handle", "id").content.contains("1b99a6c3"))
        assertTrue(!out.at("page", "history_generation").content.contains("3f8b2b69"))
    }

    @Test
    fun replacesHomeAndWorkspacePaths() {
        val out = redact(
            """{"project_path":"$home/Dev/repo/.dev/workspace",
                "nodes":[{"path":"$home/Dev/nanobot/nanobot/templates/agent/identity.md"}]}""",
        ).single()

        assertEquals("<workspace>", out.at("project_path").content)
        val node = (out.jsonObject["nodes"] as kotlinx.serialization.json.JsonArray).first().jsonObject
        assertEquals("<home>/Dev/nanobot/nanobot/templates/agent/identity.md", node["path"]!!.jsonPrimitive.content)
    }

    /**
     * The recording machine's locale. `/api/settings` carries the agent's timezone both as
     * `agent.timezone` and as the flattened `agents.defaults.timezone` in `runtime_config`, and
     * `Europe/Berlin` in a published fixture names the machine that recorded it.
     */
    @Test
    fun rewritesEveryTimezoneToUtc() {
        val out = redact(
            """{"agent":{"timezone":"Europe/Berlin"},
                "runtime_config":{"agents.defaults.timezone":"Asia/Tokyo"},
                "schedule":{"kind":"cron","expr":"0 9 * * 1","tz":"Europe/Berlin"},
                "runtime":{"timezone_mode":"auto"}}""",
        ).single()

        assertEquals("UTC", out.at("agent", "timezone").content)
        assertEquals("UTC", out.at("runtime_config", "agents.defaults.timezone").content)
        assertEquals("UTC", out.at("schedule", "tz").content, "an automation's schedule carries the same place")
        assertEquals("auto", out.at("runtime", "timezone_mode").content, "the mode is a word, not a place")
        assertTrue(
            Regex("""Europe|Asia|America|Africa|Australia|Pacific|/[A-Z][a-z]+""").find(out.toString()) == null,
            "no real zone survives anywhere in the document: $out",
        )
    }

    @Test
    fun replacesPathsInsideASentence() {
        // A tool result is prose: "Successfully wrote 7 characters to /Users/…/tool-probe.txt".
        val out = redact(
            """{"result":"Successfully wrote 7 characters to $home/Dev/repo/.dev/workspace/tool-probe.txt"}""",
        ).single()

        assertEquals(
            "Successfully wrote 7 characters to <workspace>/tool-probe.txt",
            out.at("result").content,
            "a path in the middle of a value is still this machine's path",
        )
    }

    /**
     * The vocabulary was `token, api_token, secret, password`, which misses the names a
     * gateway actually uses for a provider key and the headers a proxy logs.
     */
    @Test
    fun redactsTheWiderCredentialVocabulary() {
        val out = redact(
            """{"api_key":"sk-live-1","access_token":"at-2","refresh_token":"rt-3",
                "authorization":"Bearer abc","x-api-key":"xk-4","X-Api-Key":"xk-5",
                "client_secret":"cs-6","upload_key":"uk-7","provider":{"apiKey":"ak-8"}}""",
        ).single()

        for (name in listOf("api_key", "access_token", "refresh_token", "authorization", "client_secret", "upload_key")) {
            assertEquals("REDACTED", out.at(name).content, "$name is a credential the recorder must not commit")
        }
        assertEquals("REDACTED", out.at("x-api-key").content)
        assertEquals("REDACTED", out.at("X-Api-Key").content)
        assertEquals("REDACTED", out.at("provider", "apiKey").content, "camelCase is the same credential")
        assertTrue(out.toString().contains("sk-live-1").not(), "no key value survives: $out")
    }

    /** The row id is the one `key`-shaped name that is not a secret, so it must survive. */
    @Test
    fun aSessionKeyIsNotACredential() {
        val out = redact(
            """{"sessions":[{"key":"websocket:290bed80-cb7e-405c-8d20-4900347b55fc","session_key":"websocket:290bed80-cb7e-405c-8d20-4900347b55fc","has_api_key":false}]}""",
        ).single()

        val row = (out.jsonObject["sessions"] as kotlinx.serialization.json.JsonArray).first().jsonObject
        assertTrue(row["key"]!!.jsonPrimitive.content.startsWith("websocket:UUID-"), "the row key is an alias, not REDACTED")
        assertTrue(row["session_key"]!!.jsonPrimitive.content.startsWith("websocket:UUID-"))
        assertEquals("false", row["has_api_key"]!!.jsonPrimitive.content, "a boolean is not a secret to redact")
    }

    /**
     * The session listing is somebody's reading history. The recorder's own chats carry the
     * prompts it sent, but every other row belongs to whoever recorded, so all of them become
     * stable placeholders and the drawer still has distinct rows to draw.
     */
    @Test
    fun sessionTitlesAndPreviewsBecomePlaceholders() {
        val out = redact(
            """{"sessions":[
                {"key":"websocket:a","title":"Deploy notes for the client","preview":"can you check"},
                {"key":"websocket:b","title":"Deploy notes for the client","preview":"second session"},
                {"key":"websocket:c","title":"","preview":"untitled prompt"},
                {"key":"websocket:d","title":"","preview":""}
            ]}""",
        ).single()

        val rows = (out.jsonObject["sessions"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        val titles = rows.map { it["title"]!!.jsonPrimitive.content }
        val previews = rows.map { it["preview"]!!.jsonPrimitive.content }

        assertTrue(titles.none { it.contains("Deploy notes") }, "a real title must not survive: $titles")
        assertEquals(titles[1], titles[0], "two rows with one title keep one placeholder")
        assertTrue(Regex("""^title-\d+$""").matches(titles[0]), "a placeholder, not a redaction: $titles")
        assertTrue(Regex("""^preview-\d+$""").matches(previews[1]) && previews[1] != previews[0], "distinct previews stay distinct")
        assertEquals("", titles[2], "a blank title stays blank, because the drawer falls back to the preview then")
        assertEquals("", previews[3])
    }

    /** `/api/palette` also has `title` keys, which are command names and not anybody's history. */
    @Test
    fun theCommandPaletteKeepsItsTitles() {
        val out = redact(
            """{"commands":[{"command":"/model","title":"New chat","description":"Start a session"}]}""",
        ).single()

        val command = (out.jsonObject["commands"] as kotlinx.serialization.json.JsonArray).first().jsonObject
        assertEquals("New chat", command["title"]!!.jsonPrimitive.content)
    }

    /** The manifest used to name the gateway it was recorded from, a host in a commit. */
    @Test
    fun replacesTheRecordedGatewaysOwnAddress() {
        val out = redact(
            """{"provenance":{"gateway_url":"$gatewayUrl"},
                "readme":"open $gatewayUrl/#/chat on my-private-gateway.example or at $gatewayUrl",
                "other":"http://127.0.0.1:11434 stays, it is another loopback service"}""",
        ).single()

        assertEquals("http://gateway.example:8787", out.at("provenance", "gateway_url").content)
        val readme = out.at("readme").content
        assertTrue(readme.contains("gateway.example:8787"), "the full url and the bare host are both gone: $readme")
        assertTrue(
            out.at("other").content.startsWith("http://127.0.0.1:11434"),
            "a loopback provider endpoint identifies nobody",
        )
        assertTrue(out.toString().contains("arjmandi").not(), "no real host survives anywhere: $out")
    }

    @Test
    fun documentsEveryRuleItApplied() {
        val redactor = Redactor(homeDir = home, workspaceDir = "$home/ws", gatewayUrl = gatewayUrl)
        redactor.redact(Json.parseToJsonElement("""{"token":"nbwt_x","chat_id":"290bed80-cb7e-405c-8d20-4900347b55fc"}"""))

        val rules = redactor.rules
        assertTrue(rules.isNotEmpty(), "a fixture's manifest lists the rules that produced it")
        assertEquals(rules.distinct().size, rules.size, "each rule is listed once: $rules")
        assertTrue(rules.any { it.contains("token") }, "the rule list names what it redacted: $rules")
    }
}
