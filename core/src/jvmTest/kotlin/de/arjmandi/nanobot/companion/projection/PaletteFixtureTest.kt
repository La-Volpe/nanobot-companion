package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.fixture.Fixtures
import de.arjmandi.nanobot.companion.wire.PalettePayloads
import de.arjmandi.nanobot.companion.wire.SessionListParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray

/**
 * The palette's evidence: it is built from two recorded payloads, not from remembered
 * shapes. `palette/commands.json` is `/api/commands` and `palette/cli-apps.json` is
 * `/api/settings/cli-apps?installed_only=1`, both captured from the dev gateway and
 * indexed in the set's manifest (`manifest.reads`).
 */
class PaletteFixtureTest {

    private fun read(name: String): String = File(Fixtures.latest, name).readText()

    @Test
    fun slashListFromCommandsEndpoint() {
        val commands = PalettePayloads.commands(read("palette/commands.json"))

        assertTrue(commands.size > 5, "the gateway registers a command registry, got $commands")
        assertTrue(commands.all { it.command.startsWith("/") }, "every command is a slash token")

        val model = commands.first { it.command == "/model" }
        assertEquals("[preset]", model.argHint)
        assertTrue(model.acceptsArgs, "/model takes an argument")
        assertTrue(model.title.isNotBlank() && model.description.isNotBlank(), "a row shows a title and a description")

        // `nanobot/command/spec.py` names five lifecycles; anything else is a new protocol fact.
        val known = setOf("side_channel", "finalize_active_turn", "stop_active_turn", "agent_turn", "agent_turn_with_args")
        assertTrue(
            commands.all { it.lifecycle in known },
            "unknown lifecycle: ${commands.map { it.lifecycle }.distinct() - known}",
        )
    }

    @Test
    fun slashPaletteOfTheRecordedRegistryHasNoDuplicateTokens() {
        val commands = PalettePayloads.commands(read("palette/commands.json"))
        assertEquals(commands.size, commands.map { it.command }.distinct().size)
    }

    @Test
    fun mentionGroupsFromSessionsAndApps() {
        val rows = SessionListParser.parse(read("sessions.json"))
        val apps = PalettePayloads.installedApps(read("palette/cli-apps.json"))
        val catalog = PaletteCatalog(
            commands = PalettePayloads.commands(read("palette/commands.json")),
            sessions = PaletteCatalog.sessionsIn(rows),
            apps = apps,
        )

        val state = PaletteQuery.forComposer(catalog, "@")
        val sessions = state?.groups?.firstOrNull { it.kind == PaletteGroupKind.Sessions }

        assertTrue(rows.size > 1, "the recorded set has several sessions")
        assertEquals(rows.size, catalog.sessions.size, "every recorded session row is a mention")
        assertEquals(
            PaletteQuery.MENTION_LIMIT,
            sessions?.items?.size,
            "the sheet is eight rows whatever the drawer holds (ThreadComposer.tsx:1455)",
        )

        val first = sessions?.items?.first() as PaletteItem.Session
        val row = rows.first { it.key == first.mention.sessionKey }
        assertEquals(row.handleName, first.mention.name)
        assertEquals(row.handleId, first.mention.id)
        assertEquals(row.title.ifBlank { row.preview }, first.subtitle)
        assertTrue(first.label.startsWith("@"), "a mention token is inserted with its @")
    }

    @Test
    fun installedCliAppsAreAGroupOnlyWhenTheGatewayHasSome() {
        val apps = PalettePayloads.installedApps(read("palette/cli-apps.json"))
        val state = PaletteQuery.forComposer(PaletteCatalog(sessions = listOf(session())), "@")

        assertTrue(apps.isEmpty(), "this gateway has no CLI app installed, and the fixture says so")
        assertTrue(
            state?.groups?.none { it.kind == PaletteGroupKind.CliApps } == true,
            "an empty group is not a row in the sheet",
        )
    }

    /** The endpoint's envelope, so a future shape change fails here rather than at runtime. */
    @Test
    fun cliAppsPayloadCarriesAnAppsArray() {
        val document = Fixtures.readJson(File(Fixtures.latest, "palette/cli-apps.json"))
        assertTrue(document["apps"] is JsonArray, "/api/settings/cli-apps answers {apps: [...]}")
        assertTrue(document["installed_count"] != null, "…and counts what it listed")
    }

    private fun session(): SessionMention =
        SessionMention(id = "handle_1", name = "fomo", sessionKey = "websocket:a", title = "Title")
}
