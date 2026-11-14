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
 * machine or this login has to go (plan.md §3.3, C2.5).
 */
class RedactorTest {

    private val home = "/Users/someone"

    private fun redact(vararg documents: String): List<JsonElement> {
        val redactor = Redactor(homeDir = home, workspaceDir = "$home/Dev/repo/.dev/workspace")
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
        // C5.1 sorts sessions by updated_at, so normalisation must not collapse them.
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
        assertTrue(Regex("""^stream-\d+$""").matches(stream), "D15.3: stream_id is opaque, so an alias keeps its purpose: $stream")
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

    @Test
    fun documentsEveryRuleItApplied() {
        val redactor = Redactor(homeDir = home, workspaceDir = "$home/ws")
        redactor.redact(Json.parseToJsonElement("""{"token":"nbwt_x","chat_id":"290bed80-cb7e-405c-8d20-4900347b55fc"}"""))

        val rules = redactor.rules
        assertTrue(rules.isNotEmpty(), "a fixture's manifest lists the rules that produced it")
        assertEquals(rules.distinct().size, rules.size, "each rule is listed once: $rules")
        assertTrue(rules.any { it.contains("token") }, "the rule list names what it redacted: $rules")
    }
}
