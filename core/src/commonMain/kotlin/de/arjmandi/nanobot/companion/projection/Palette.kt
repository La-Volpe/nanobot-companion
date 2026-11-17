package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.CliAppMention
import de.arjmandi.nanobot.companion.wire.SessionRow
import de.arjmandi.nanobot.companion.wire.SlashCommand

/**
 * A chat that can be named in a prompt (`@fomo`). The gateway turns one of these back into a
 * readable session (`transcript.py` `normalize_session_mentions_metadata`), so `sessionKey`
 * is the durable half and the handle name is the token a person types.
 */
public data class SessionMention(
    public val id: String?,
    public val name: String,
    public val sessionKey: String,
    public val title: String,
)

/** Everything the two palettes are drawn from, refreshed on demand. */
public data class PaletteCatalog(
    public val commands: List<SlashCommand> = emptyList(),
    public val sessions: List<SessionMention> = emptyList(),
    public val apps: List<CliAppMention> = emptyList(),
) {
    /** The commands and apps are their own read; the sessions ride the drawer's payload. */
    public fun withReads(commands: List<SlashCommand>, apps: List<CliAppMention>): PaletteCatalog =
        copy(commands = commands, apps = apps)

    public companion object {
        /**
         * Session rows become mentions through their `handle`: a row without one cannot be
         * named in a prompt (`ThreadComposer.tsx` `sessionMentionOptions`).
         */
        public fun sessionsIn(rows: List<SessionRow>): List<SessionMention> = rows.mapNotNull { row ->
            val name = row.handleName ?: return@mapNotNull null
            SessionMention(
                id = row.handleId,
                name = name,
                sessionKey = row.key,
                title = row.title.ifBlank { row.preview },
            )
        }
    }
}

public enum class PaletteMode { Slash, Mention }

/** Group titles are copy, so the platform holds the sentence. */
public enum class PaletteGroupKind(public val copyKey: String) {
    Commands("palette.commands"),
    Sessions("palette.sessions"),
    CliApps("palette.cliApps"),
}

public sealed interface PaletteItem {
    /** What the composer inserts for this row. */
    public val label: String

    /** The line under the label; never empty, because a row with no second line looks broken. */
    public val subtitle: String

    public data class Command(public val command: SlashCommand) : PaletteItem {
        override val label: String get() = command.command
        override val subtitle: String get() = command.title
    }

    public data class Session(public val mention: SessionMention) : PaletteItem {
        override val label: String get() = "@" + mention.name
        override val subtitle: String get() = mention.title.ifBlank { mention.name }
    }

    public data class App(public val app: CliAppMention) : PaletteItem {
        override val label: String get() = "@" + app.name
        override val subtitle: String get() = app.displayName
    }
}

public data class PaletteGroup(public val kind: PaletteGroupKind, public val items: List<PaletteItem>)

/**
 * The open palette: which one, what has been typed, and the range the platform replaces when
 * a row is picked. `end` is the caret, so a mention typed mid-sentence keeps the words after it.
 */
public data class PaletteState(
    public val mode: PaletteMode,
    public val token: String,
    public val start: Int,
    public val end: Int,
    public val groups: List<PaletteGroup>,
) {
    public val items: List<PaletteItem> get() = groups.flatMap { it.items }
}

/**
 * One query model shared by both palettes. Pure: the composer's text and caret
 * in, the open sheet out; `null` means no palette is open.
 *
 * The rules are the WebUI's, read from its source rather than chosen here: the slash palette
 * opens on a value that starts with `/` and has no space in the command token
 * (`ThreadComposer.tsx:1115`), a mention opens on `@` at the start of a word
 * (`composer-mention-text.ts:5`), and the filter is a case-insensitive *substring* over the
 * row's own words, not a prefix (`ThreadComposer.tsx:1407-1455`).
 */
public object PaletteQuery {

    /** The sheet shows eight rows, and the WebUI reserves a share per group (`ThreadComposer.tsx:1455`). */
    public const val MENTION_LIMIT: Int = 8

    private const val SESSION_ROWS: Int = 4
    private const val APP_ROWS: Int = 2

    /** The characters after which an `@` starts a mention; an address is not one. */
    private const val MENTION_BOUNDARY: String = " \t\n([{,，、;；:：!?！？。"

    private val MENTION_TOKEN: Regex = Regex("@([\\p{L}\\p{N}_-]*)$")

    public fun forComposer(catalog: PaletteCatalog, text: String, cursor: Int = text.length): PaletteState? =
        slash(catalog, text) ?: mention(catalog, text, cursor)

    private fun slash(catalog: PaletteCatalog, text: String): PaletteState? {
        if (!text.startsWith("/")) return null
        val token = text.drop(1)
        if (token.any { it.isWhitespace() }) return null
        val query = token.lowercase()
        // Same rule as the mention filter, and the WebUI's own: a substring over the row's
        // words (`command`, `title`, `description`, `arg_hint`), so `/set` finds `/model`.
        val matches = catalog.commands.filter { command -> commandMatches(command, query) }
        return PaletteState(
            mode = PaletteMode.Slash,
            token = token,
            start = 0,
            end = text.length,
            groups = listOf(PaletteGroup(PaletteGroupKind.Commands, matches.map { PaletteItem.Command(it) })),
        )
    }

    private fun mention(catalog: PaletteCatalog, text: String, cursor: Int): PaletteState? {
        val caret = cursor.coerceIn(0, text.length)
        val before = text.substring(0, caret)
        val match = MENTION_TOKEN.find(before) ?: return null
        val at = match.range.first
        if (at > 0 && text[at - 1] !in MENTION_BOUNDARY) return null
        val query = match.groupValues[1].lowercase()

        // A CLI app whose name is also a session's name would be the same token twice; the
        // WebUI keeps the session (`ThreadComposer.tsx:1424`).
        val sessionNames = catalog.sessions.map { it.name.lowercase() }.toSet()
        val sessions = catalog.sessions.filter { mentionMatches(query, it.name, it.title) }
        val apps = catalog.apps.filter { it.name.lowercase() !in sessionNames }
            .filter { mentionMatches(query, it.name, it.displayName, it.category, it.description, it.entryPoint) }

        val groups = cappedRows(sessions, apps)
        return PaletteState(
            mode = PaletteMode.Mention,
            token = query,
            start = at,
            end = caret,
            groups = groups,
        )
    }

    /** Session rows first, then apps; each keeps a reserved share and takes what is left in order. */
    private fun cappedRows(sessions: List<SessionMention>, apps: List<CliAppMention>): List<PaletteGroup> {
        val reserved = listOf(sessions.size.coerceAtMost(SESSION_ROWS), apps.size.coerceAtMost(APP_ROWS))
        var remaining = MENTION_LIMIT - reserved.sum()
        val candidates = listOf(sessions.size, apps.size)
        val counts = reserved.toMutableList()
        for (index in candidates.indices) {
            if (remaining <= 0) break
            val extra = (candidates[index] - counts[index]).coerceIn(0, remaining)
            counts[index] += extra
            remaining -= extra
        }
        return listOfNotNull(
            sessions.take(counts[0]).map { PaletteItem.Session(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { PaletteGroup(PaletteGroupKind.Sessions, it) },
            apps.take(counts[1]).map { PaletteItem.App(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { PaletteGroup(PaletteGroupKind.CliApps, it) },
        )
    }

    private fun commandMatches(command: SlashCommand, query: String): Boolean = listOf(
        command.command,
        command.title,
        command.description,
        command.argHint,
    ).joinToString(" ").lowercase().contains(query)

    /** `includes` over the row's words, lowercased on both sides: the WebUI's filter. */
    private fun mentionMatches(query: String, vararg words: String?): Boolean =
        words.filterNotNull().joinToString(" ").lowercase().contains(query)
}
