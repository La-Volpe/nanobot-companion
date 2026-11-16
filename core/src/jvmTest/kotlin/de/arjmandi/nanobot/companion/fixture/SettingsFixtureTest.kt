package de.arjmandi.nanobot.companion.fixture

import de.arjmandi.nanobot.companion.facade.UsageWindow
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

    /**
     * A fixture is committed, so the recording machine's locale must not be in it (T26): the
     * settings payload carries the agent's timezone twice, once flattened into `runtime_config`.
     */
    @Test
    fun noFixtureCarriesARealTimezone() {
        val zones = Regex("""Europe|Asia|America|Africa|Australia|Pacific|Atlantic|Indian/""")
        val leaking = Fixtures.files(Fixtures.latest).filter { zones.containsMatchIn(it.readText()) }

        assertTrue(leaking.isEmpty(), "a real zone names the machine that recorded it: $leaking")
        assertTrue(
            Fixtures.readJson(File(Fixtures.latest, "settings.json")).toString().contains("\"timezone\":\"UTC\""),
            "the field is still there, normalised",
        )
    }

    /**
     * T44: the card draws the recorded days in the WebUI's 30-day window. The window ends on a date
     * the phone supplies; anchored on the recording's own last day, the strip is thirty slots and
     * the headline is the day the gateway reported — the payload carries a year of days.
     */
    @Test
    fun theRecordedUsageFillsTheCardsWindow() {
        val usage = SettingsPayloads.usage(read("settings-usage.json"))
        assertTrue(usage.days.isNotEmpty(), "the recording has usage to show")

        val summary = UsageWindow.last30(usage, todayIso = usage.days.maxOf { it.date })

        assertEquals(UsageWindow.DAYS, summary.days.size, "a bar for every day of the window")
        assertEquals(usage.days.sumOf { it.totalTokens }, summary.totalTokens, "the slots sum to the days that were in it")
        assertTrue(summary.hasUsage, "the recording spent tokens")
    }

    /**
     * The model sheet is drawn from `model_presets` in the settings payload the client already
     * reads (I5). The alternative — a hand-written list of presets in the app — would offer a
     * `/model` switch to a name the gateway does not have.
     */
    @Test
    fun theModelPresetsComeFromTheRecordedSettings() {
        val state = SettingsPayloads.settings(read("settings.json"))

        assertTrue(state.modelPresets.size >= 2, "the recording has more than the default preset: ${state.modelPresets.map { it.name }}")
        val named = state.modelPresets.map { it.name }
        assertTrue(named.first() == "default", "the gateway always lists `default` first: $named")
        assertTrue(
            state.modelPresets.all { it.name.isNotBlank() && it.model.isNotBlank() },
            "a preset row needs the name to switch to and the model it answers with",
        )
        val primary = state.modelPresets.firstOrNull { it.name == "primary" }
        assertTrue(primary != null, "the dev gateway's own preset is in the list: $named")
        assertEquals("deepseek-v4.1-flash:cloud", primary?.model)
        assertEquals(32768, primary?.contextWindowTokens, "each preset carries its own context window, which the sheet shows")
        assertTrue(
            state.modelPresets.count { it.active } == 1,
            "one preset is the active one: ${state.modelPresets.filter { it.active }.map { it.name }}",
        )
    }

    @Test
    fun transcriptionBoundsMatchTheProtocol() {
        val state = SettingsPayloads.settings(read("settings.json"))
        // C6.5's 120 s bound is the gateway's own, so the two must agree.
        assertEquals(120, state.transcription?.maxDurationSeconds)
    }
}
