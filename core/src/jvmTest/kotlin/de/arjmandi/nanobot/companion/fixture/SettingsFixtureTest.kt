package de.arjmandi.nanobot.companion.fixture

import de.arjmandi.nanobot.companion.wire.SettingsPayloads
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C7.1's evidence. `settings.json` and `settings-usage.json` are `/api/settings` and
 * `/api/settings/usage` read from the dev gateway and indexed under `manifest.reads`; the
 * assertions are the ones the Overview screen depends on.
 */
class SettingsFixtureTest {

    private fun read(name: String): String = File(Fixtures.latest, name).readText()

    @Test
    fun versionAndEnvironmentFromTheRecordedSettings() {
        val state = SettingsPayloads.settings(read("settings.json"))

        assertEquals("0.4.1", state.version?.current, "the gateway version is the docs' version")
        assertTrue(!state.version?.commit.isNullOrBlank(), "the commit is redacted to an alias, but present")
        assertEquals("Darwin", state.environment?.os)
        assertTrue(!state.environment?.pythonVersion.isNullOrBlank())

        // D11: the manifest carries the real revision, so the alias inside the payload loses nothing.
        assertTrue(
            (Fixtures.manifest()["provenance"].obj().str("gateway_commit") ?: "").isNotBlank(),
            "provenance.gateway_commit is the record of which revision this set came from",
        )
    }

    @Test
    fun theRecordedSettingsHaveNoUptimeField() {
        val document = Fixtures.readJson(File(Fixtures.latest, "settings.json"))
        val uptime = document["uptime"]
        assertTrue(uptime == null, "the gateway's settings payload has no uptime; Overview shows version and server")
        assertTrue(
            document.keys.none { it.contains("uptime", ignoreCase = true) },
            "no field mentions uptime at all",
        )
    }

    @Test
    fun usageSourcesAreTheGatewaysOwnNames() {
        val usage = SettingsPayloads.usage(read("settings-usage.json"))
        val sources = usage.days.flatMap { it.sources.keys }.toSet()

        // `nanobot/llm_usage/context.py:8` names five; a recording may only have used some.
        val known = setOf("user", "api", "cron", "dream", "system")
        assertTrue(sources.isNotEmpty(), "the recording has usage to show")
        assertTrue(sources.all { it in known }, "unknown usage source: ${sources - known}")

        val day = usage.days.first()
        assertEquals(usage.totalTokens, usage.days.sumOf { it.totalTokens }, "the day sums are the total")
        assertEquals(day.totalTokens, day.sources.values.sumOf { it.totalTokens }, "and each day's sources sum to the day")
    }

    @Test
    fun transcriptionBoundsMatchTheProtocol() {
        val state = SettingsPayloads.settings(read("settings.json"))
        // C6.5's 120 s bound is the gateway's own, so the two must agree.
        assertEquals(120, state.transcription?.maxDurationSeconds)
    }
}
