package de.arjmandi.nanobot.companion.fixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A plain streaming turn was all the set carried, which left tool rows (C4.4), notices
 * (C7.3) and the error taxonomy (C7.4) written from the gateway source rather than from
 * what it actually sends. These assertions are the reason the recorder grew scenarios
 * (P3): each one names the frames a later task needs, so a scenario that stops producing
 * them fails the build instead of silently hollowing out the fixture set.
 */
class ScenarioFixturesTest {

    @Test
    fun toolScenarioRecordsRunningAndFinishedToolEvents() {
        val frames = Fixtures.scenarioFrames("tool")
        assertTrue(frames.isNotEmpty(), "no tool scenario recorded — run :core-cli record")

        val toolEvents = frames.flatMap { frame -> frame.array("tool_events").map { it.obj() } }
        assertTrue(toolEvents.isNotEmpty(), "the tool scenario produced no tool_events; got ${frames.map { it.event() }}")

        // C4.4 turns start into a running row and end into a settled one, so both phases
        // have to be in evidence.
        val phases = toolEvents.mapNotNull { it.str("phase") }
        assertTrue("start" in phases, "no running tool row in the recording; phases were $phases")
        assertTrue("end" in phases, "no finished tool row in the recording; phases were $phases")

        val started = toolEvents.first { it.str("phase") == "start" }
        assertTrue(!started.str("name").isNullOrBlank(), "a tool row needs the tool's name to render (C4.4)")
        assertTrue(started["call_id"] != null, "start and end of one call are joined by call_id")
        assertTrue(
            toolEvents.any { it.str("phase") == "end" && it["result"]?.let { r -> r !is JsonPrimitive || r.isString } == true },
            "the finished row carries the tool's result, which the activity row shows",
        )
    }

    @Test
    fun failureScenarioRecordsAFailedTurnEnd() {
        val frames = Fixtures.scenarioFrames("failure")
        assertTrue(frames.isNotEmpty(), "no failure scenario recorded — run :core-cli record")

        val end = frames.filter { it.event() == "turn_end" }.lastOrNull()
        assertTrue(end != null, "the failure scenario never settled; got ${frames.map { it.event() }}")
        assertEquals("failed", end!!.str("outcome"), "C4.5 settles a failed turn from turn_end.outcome")
        assertTrue(!end.str("failure_kind").isNullOrBlank(), "the retry copy is chosen by failure_kind")
        assertTrue(!end.str("failure_message").isNullOrBlank(), "the gateway ships the message a user reads")

        // D15: a model failure arrives with the retry that gave up on it.
        assertTrue(
            frames.any { it.event() == "retry_status" && !it.str("error_kind").isNullOrBlank() },
            "no retry_status with an error_kind; got ${frames.map { it.event() }}",
        )
    }

    @Test
    fun errorScenarioRecordsTheGatewayErrorFrame() {
        val frames = Fixtures.scenarioFrames("error")
        assertTrue(frames.isNotEmpty(), "no error scenario recorded — run :core-cli record")

        val error = frames.firstOrNull { it.event() == "error" }
        assertTrue(error != null, "the error scenario recorded no error frame; got ${frames.map { it.event() }}")
        assertTrue(!error!!.str("detail").isNullOrBlank(), "error.detail is what the taxonomy is keyed on (C7.4)")
        // The recording, not the docs, says this: a rejection is reported with `detail`
        // alone. C7.4 must not require a chat id the gateway never sends.
        assertNull(error.str("chat_id"), "a rejection frame names no chat; the taxonomy keys on detail")
    }

    @Test
    fun everyRecordedScenarioIsNamedInTheManifest() {
        val set = Fixtures.latest
        val named = Fixtures.scenarioNames(set)

        assertTrue("plain" in named, "the plain turn is a scenario too; got $named")
        assertEquals(named.distinct().sorted(), named.sorted(), "a scenario is recorded once per set")
        for (scenario in named) {
            assertTrue(
                Fixtures.scenarioFrames(scenario, set).isNotEmpty(),
                "manifest names scenario '$scenario' but recorded no frames for it",
            )
        }
        // The manifest is the index review reads; every frame directory has to be in it.
        val dirs = set.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { if (it.name == "ws") "plain" else it.name }
            .sorted()
        assertEquals(dirs, named.sorted(), "every frame directory in the set belongs to a named scenario")
    }

    private fun JsonObject.event(): String = str("event") ?: "<missing>"

    private fun JsonObject.array(key: String): List<JsonElement> =
        this[key] as? JsonArray ?: emptyList()
}
