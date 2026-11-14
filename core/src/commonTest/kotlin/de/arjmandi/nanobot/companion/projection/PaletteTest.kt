package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.CliAppMention
import de.arjmandi.nanobot.companion.wire.SlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two composer palettes (plan.md C6.4): `/` lists the gateway's commands, `@` lists the
 * sessions and the installed CLI apps. The rules here are the WebUI's own
 * (`ThreadComposer.tsx` `slashQuery`, `filteredMentionCandidates`,
 * `composer-mention-text.ts` `isCapabilityMentionStart`), transcribed rather than invented:
 * a mention opens only at a word boundary, the filter is a case-insensitive substring over
 * the row's own words, and the sheet shows eight rows with reserved shares per group.
 */
class PaletteTest {

    private val catalog = PaletteCatalog(
        commands = listOf(
            command("/model", "Switch model preset", argHint = "[preset]", acceptsArgs = true),
            command("/compact", "Compact context"),
            command("/stop", "Stop current task"),
            command("/goal", "Start long-running goal", acceptsArgs = true),
        ),
        sessions = listOf(
            SessionMention(id = "handle_1", name = "fomo", sessionKey = "websocket:a", title = "Exact word pong exchange"),
            SessionMention(id = "handle_2", name = "jana", sessionKey = "websocket:b", title = "Drawer redesign notes"),
        ),
        apps = listOf(
            CliAppMention(name = "ffmpeg", displayName = "FFmpeg", category = "media", description = "Transcode video"),
            CliAppMention(name = "docx", displayName = "Word documents", category = "docs", description = "Write a .docx"),
        ),
    )

    @Test
    fun slashOpensOnAnEmptyTokenAndListsEveryCommand() {
        val state = PaletteQuery.forComposer(catalog, "/")
        assertEquals(PaletteMode.Slash, state?.mode)
        assertEquals(4, state?.items?.size)
        assertEquals(PaletteGroupKind.Commands, state?.groups?.single()?.kind)
    }

    @Test
    fun slashFiltersByTheTokenAfterTheSlash() {
        val state = PaletteQuery.forComposer(catalog, "/mo")
        assertEquals(listOf("/model"), state?.items?.map { it.label })
        assertEquals(0, state?.start)
        assertEquals(3, state?.end)
    }

    @Test
    fun slashFiltersOverTheRowsWordsNotJustTheToken() {
        // `ThreadComposer.tsx:1205-1218` filters `[command, title, description, arg_hint]`:
        // a word from the description finds the command.
        val byDescription = PaletteQuery.forComposer(
            catalog.copy(
                commands = catalog.commands + command("/compact", "Compact context").copy(
                    description = "Compact this chat's context and continue the conversation.",
                ),
            ),
            "/convers",
        )
        assertEquals(listOf("/compact"), byDescription?.items?.map { it.label })
    }

    @Test
    fun slashClosesOnceTheCommandHasAnArgument() {
        assertNull(PaletteQuery.forComposer(catalog, "/model primary"), "a space ends the command token")
    }

    @Test
    fun mentionGroupsFromSessionsAndApps() {
        val state = PaletteQuery.forComposer(catalog, "@")
        assertEquals(PaletteMode.Mention, state?.mode)
        assertEquals(
            listOf(PaletteGroupKind.Sessions, PaletteGroupKind.CliApps),
            state?.groups?.map { it.kind },
        )
        assertEquals(listOf("@fomo", "@jana"), state?.groups?.first()?.items?.map { it.label })
        assertEquals("@ffmpeg", state?.groups?.last()?.items?.first()?.label)
    }

    @Test
    fun filterIsCaseInsensitive() {
        val state = PaletteQuery.forComposer(catalog, "@FO")
        assertEquals(listOf("@fomo"), state?.items?.map { it.label })
    }

    @Test
    fun filterMatchesAnyWordOfTheRowNotJustThePrefix() {
        // `ThreadComposer.tsx:1407-1417` filters `[name, title].join(" ")` with `includes`:
        // a query that appears only inside the title still finds the session.
        val byTitle = PaletteQuery.forComposer(catalog, "@redesign")
        assertEquals(listOf("@jana"), byTitle?.items?.map { it.label })

        val byDescription = PaletteQuery.forComposer(catalog, "@transcode")
        assertEquals(listOf("@ffmpeg"), byDescription?.items?.map { it.label })
    }

    @Test
    fun mentionOpensOnlyAtAWordBoundary() {
        assertNull(
            PaletteQuery.forComposer(catalog, "mail me at javad@"),
            "an address is not a mention: the @ follows a letter",
        )
        assertEquals(
            PaletteMode.Mention,
            PaletteQuery.forComposer(catalog, "ask @ja")?.mode,
            "after a space the @ opens the palette",
        )
    }

    @Test
    fun replaceRangeCoversTheTypedTokenOnly() {
        val text = "ask @fo and wait"
        val state = PaletteQuery.forComposer(catalog, text, cursor = 7)
        assertEquals(4, state?.start)
        assertEquals(7, state?.end)
        assertEquals("fo", state?.token)
    }

    @Test
    fun appNamedLikeASessionIsDropped() {
        val clashing = catalog.copy(
            apps = catalog.apps + CliAppMention(name = "FOMO", displayName = "Fomo CLI", category = "x", description = ""),
        )
        val state = PaletteQuery.forComposer(clashing, "@")
        assertEquals(
            listOf("@ffmpeg", "@docx"),
            state?.groups?.last()?.items?.map { it.label },
            "a CLI app whose name is a session's name would be two identical tokens",
        )
    }

    @Test
    fun eightRowsWithSessionsKeepingTheirShare() {
        val many = catalog.copy(
            sessions = (1..9).map { SessionMention(id = "h$it", name = "s$it", sessionKey = "websocket:s$it", title = "session $it") },
            apps = (1..5).map { CliAppMention(name = "app$it", displayName = "App $it", category = "c", description = "") },
        )
        val state = PaletteQuery.forComposer(many, "@")
        val items = state?.items.orEmpty()
        assertEquals(PaletteQuery.MENTION_LIMIT, items.size)
        // Two rows go to the app group before the crowded session group takes the rest
        // (`ThreadComposer.tsx:1455`): a long session list must not hide every app.
        assertEquals(6, items.count { it is PaletteItem.Session })
        assertEquals(2, items.count { it is PaletteItem.App })

        val withoutApps = PaletteQuery.forComposer(many.copy(apps = emptyList()), "@")
        assertEquals(PaletteQuery.MENTION_LIMIT, withoutApps?.items?.size, "an empty group frees its rows")
        assertEquals(PaletteQuery.MENTION_LIMIT, withoutApps?.items?.count { it is PaletteItem.Session })
    }

    @Test
    fun emptyQueryKeepsTheGroupsInOrderWithoutAnEmptyGroup() {
        val sessionsOnly = catalog.copy(apps = emptyList())
        val state = PaletteQuery.forComposer(sessionsOnly, "@")
        assertEquals(listOf(PaletteGroupKind.Sessions), state?.groups?.map { it.kind })
        assertTrue(state?.items?.isNotEmpty() == true)
    }

    @Test
    fun noPaletteWithoutAPrefix() {
        assertNull(PaletteQuery.forComposer(catalog, "hello"))
        assertNull(PaletteQuery.forComposer(catalog, ""))
    }

    private fun command(
        name: String,
        title: String,
        argHint: String = "",
        acceptsArgs: Boolean = false,
    ): SlashCommand = SlashCommand(
        command = name,
        title = title,
        description = "$title.",
        icon = "square",
        argHint = argHint,
        lifecycle = "side_channel",
        acceptsArgs = acceptsArgs,
    )
}
