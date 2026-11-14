package de.arjmandi.nanobot.companion.fixture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * C2.5's gate: the fixtures are evidence only if each one says where it came from
 * and carries no live credential or run-to-run churn (plan.md §3.3).
 *
 * Every assertion here fails today because no fixture set exists yet; the set is
 * produced by `:core-cli record` against a gateway started by tools/nanobot-dev.sh.
 */
class FixtureManifestTest {

    @Test
    fun everyFixtureHasProvenance() {
        assertTrue(Fixtures.sets.isNotEmpty(), "no fixture sets under fixtures/ — run :core-cli record")

        for (set in Fixtures.sets) {
            val manifest = Fixtures.manifest(set)
            val provenance = manifest["provenance"].obj()

            for (key in listOf("recorded_at", "gateway_version", "gateway_commit", "model", "recorder")) {
                val value = provenance.str(key)
                assertTrue(
                    !value.isNullOrBlank(),
                    "${set.name}/manifest.json: provenance.$key missing or blank",
                )
            }

            // The directory name is the provenance date and gateway revision, so a
            // copied fixture cannot lose its origin by being renamed.
            val date = provenance.str("recorded_at")!!.substringBefore('T')
            val expectedName = "$date-${provenance.str("gateway_commit")}"
            assertEquals(expectedName, set.name, "fixture directory name disagrees with its manifest")

            assertTrue(
                manifest["redaction"].obj()["rules"].let { it is JsonArray && it.isNotEmpty() },
                "${set.name}/manifest.json: redaction.rules must document what was normalised",
            )
        }
    }

    @Test
    fun manifestListsEveryFileOnDisk() {
        val set = Fixtures.latest
        val listed = Fixtures.manifest(set)["files"] as JsonArray
        val onDisk = Fixtures.files(set)
            .filter { it.name != "manifest.json" }
            .associateBy { it.relativeTo(set).path }

        assertEquals(onDisk.keys.sorted(), listed.map { it.obj().str("path")!! }.sorted())

        for (entry in listed.map { it.obj() }) {
            val path = entry.str("path")!!
            val file = onDisk[path] ?: File(set, path)
            assertEquals(file.length(), (entry["bytes"] as JsonPrimitive).content.toLong(), "$path: byte count")
        }
    }

    @Test
    fun recordsOneTurnInGatewayOrder() {
        val events = Fixtures.events()

        assertEquals("ready", events.first(), "a recording starts with the socket's ready frame")
        assertEquals("turn_end", events.last(), "a recording ends when the turn settles")
        assertEquals(events, (Fixtures.manifest()["events"] as JsonArray).map { (it as JsonPrimitive).content })

        // D15.2: this is the order the live gateway emits for one real turn.
        for (required in listOf("message_accepted", "turn_model_updated", "model_request", "delta", "stream_end", "turn_end")) {
            assertTrue(events.contains(required), "recording is missing $required; got $events")
        }
    }

    @Test
    fun redactionLeavesNoSecretOrChurningValue() {
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val longNumber = Regex("(?<![\\d.])\\d{12,}")

        for (set in Fixtures.sets) {
            for (file in Fixtures.files(set)) {
                val text = file.readText()
                val where = "${set.name}/${file.relativeTo(set).path}"
                assertTrue(!uuid.containsMatchIn(text), "$where still contains a uuid")
                assertTrue(!longNumber.containsMatchIn(text), "$where still contains an epoch-millis value")
                assertTrue(!text.contains("nbwt"), "$where still contains a bootstrap token prefix")
            }
        }

        val bootstrap = Fixtures.readJson(File(Fixtures.latest, "bootstrap.json"))
        assertEquals("REDACTED", bootstrap.str("token"), "bootstrap token must not be in the fixture")
        assertEquals("REDACTED", bootstrap.str("api_token"), "api token must not be in the fixture")
    }

    @Test
    fun redactionLeavesNoMachinePath() {
        // A tool result is prose containing a path in the middle of a sentence, so a rule
        // that only rewrites a value that *starts with* the container path misses it. The
        // rule is "the path never appears", not "the path never appears at the front".
        val machinePath = Regex("/(Users|home)/[^\"\\s]+")

        for (set in Fixtures.sets) {
            for (file in Fixtures.files(set)) {
                val text = file.readText()
                val match = machinePath.find(text)
                assertNull(match, "${set.name}/${file.relativeTo(set).path} still contains ${match?.value}")
            }
        }
    }

    @Test
    fun bootstrapCarriesContractAndLimits() {
        val bootstrap = Fixtures.readJson(File(Fixtures.latest, "bootstrap.json"))
        val webui = bootstrap["terminal"].obj()["webui"].obj()

        assertEquals(1, (webui["min_protocol"] as JsonPrimitive).content.toInt())
        assertTrue(
            (webui["capabilities"] as JsonArray).any { (it as JsonPrimitive).content == "webui.core.v1" },
            "webui.core.v1 is the capability every client requires (D15.1)",
        )
        assertTrue(bootstrap["runtime_capabilities"].obj().isNotEmpty(), "D15.1: host action flags are a dict")
        assertTrue(bootstrap["runtime_capabilities"].obj()["can_restart_engine"] != null, "D15.1: host flags stay separate")

        val attachments = bootstrap["limits"].obj()["attachments"].obj()
        assertEquals(4, (attachments["max_count"] as JsonPrimitive).content.toInt())
        assertEquals(6_291_456L, (attachments["max_file_bytes"] as JsonPrimitive).content.toLong())
        assertEquals(25_165_824L, (attachments["max_total_bytes"] as JsonPrimitive).content.toLong())
    }

    @Test
    fun sessionsPageCarriesIsoTimestampsAndKeys() {
        val rows = Fixtures.readJson(File(Fixtures.latest, "sessions.json"))["sessions"] as JsonArray
        val iso = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?$""")

        assertTrue(rows.isNotEmpty(), "a recording needs at least the session it created")
        for (row in rows.map { it.obj() }) {
            assertTrue(row.str("key")!!.startsWith("websocket:"), "D15.6: session rows are keyed websocket:<id>")
            assertTrue(iso.matches(row.str("created_at")!!), "D15.2: created_at is an ISO-8601 string")
            assertTrue(iso.matches(row.str("updated_at")!!), "D15.2: updated_at is an ISO-8601 string")
            assertTrue(row["workspace_scope"].obj()["access_mode"] != null, "D15.6: workspace_scope nests access_mode")
        }
    }

    @Test
    fun historyPageReplaysTheRecordedTurn() {
        val history = Fixtures.readJson(File(Fixtures.latest, "history.json"))
        val events = history["events"] as JsonArray
        val turnId = Fixtures.manifest()["session"].obj().str("turn_id")

        assertTrue(events.isNotEmpty(), "history must replay the turn the socket just streamed")
        for (entry in events.map { it.obj() }) {
            assertTrue(entry.str("event") != null, "history events use the socket's vocabulary")
            assertEquals(turnId, entry.str("turn_id"), "history must be the same turn as the live recording")
            assertTrue(entry["turn_seq"] != null, "D15: history carries turn_seq for ordering")
        }
        assertTrue(
            events.map { it.obj().str("event") }.containsAll(listOf("user_message", "turn_end")),
            "history must contain the prompt and the settle event",
        )
    }
}
