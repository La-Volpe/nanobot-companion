package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The slash palette's rows (`GET /api/commands`). The gateway builds them from its command
 * registry (`nanobot/command/application.py` `builtin_command_palette`), so the list is the
 * gateway's, not this client's — a new builtin appears in the sheet without an app release.
 */
public data class SlashCommand(
    public val command: String,
    public val title: String,
    public val description: String,
    public val icon: String?,
    public val argHint: String,
    /**
     * How the command participates in turn state (`nanobot/command/spec.py`): `side_channel`,
     * `finalize_active_turn`, `stop_active_turn`, `agent_turn`, `agent_turn_with_args`.
     */
    public val lifecycle: String,
    public val acceptsArgs: Boolean,
)

/**
 * One installed CLI app as a mention (`GET /api/settings/cli-apps?installed_only=1`). The
 * gateway's item carries more (`cli_apps_api.py` `CliAppInfo`); these are the fields the
 * mention sheet and the substring filter read.
 */
public data class CliAppMention(
    public val name: String,
    public val displayName: String,
    public val category: String,
    public val description: String,
    public val entryPoint: String? = null,
)

/** Payload readers for the two read endpoints the palettes are built from (C6.4). */
public object PalettePayloads {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    public fun commands(text: String): List<SlashCommand> =
        (root(text)?.items("commands") ?: emptyList()).mapNotNull { item ->
            val command = item.text("command")?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
            SlashCommand(
                command = command,
                title = item.text("title").orEmpty(),
                description = item.text("description").orEmpty(),
                icon = item.text("icon"),
                argHint = item.text("arg_hint").orEmpty(),
                lifecycle = item.text("lifecycle").orEmpty(),
                acceptsArgs = item.flag("accepts_args"),
            )
        }

    /** Installed only: `?installed_only=1` already filters, and a row that says otherwise is dropped. */
    public fun installedApps(text: String): List<CliAppMention> =
        (root(text)?.items("apps") ?: emptyList()).mapNotNull { item ->
            val name = item.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (item["installed"] != null && !item.flag("installed")) return@mapNotNull null
            CliAppMention(
                name = name,
                displayName = item.text("display_name") ?: name,
                category = item.text("category").orEmpty(),
                description = item.text("description").orEmpty(),
                entryPoint = item.text("entry_point"),
            )
        }

    private fun root(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
