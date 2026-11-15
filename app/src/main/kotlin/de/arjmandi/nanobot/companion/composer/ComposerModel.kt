package de.arjmandi.nanobot.companion.composer

/**
 * What the composer row shows (plan.md A5), as Kotlin's own values. The bridge maps the core's
 * `ComposerState` onto this, so no composable or view model holds a facade type.
 */
data class ComposerSnapshot(
    val modelName: String? = null,
    val modelPreset: String? = null,
    val contextWindowTokens: Int? = null,
    /** `turn_end.usage.context_tokens`: the ring's numerator (D15.4). */
    val usedTokens: Int? = null,
    /** The core's `Math.min(100, Math.round(used / window * 100))`; the ring draws this (T37). */
    val contextPercent: Int? = null,
    val access: WorkspaceAccess = WorkspaceAccess.Default,
    /** The prompt waiting for the running turn to end, if there is one. */
    val queued: String? = null,
    /** `ready.limits.attachments`: the composer validates against the gateway's numbers (C3.7). */
    val limits: de.arjmandi.nanobot.companion.attachment.UploadLimits = de.arjmandi.nanobot.companion.attachment.UploadLimits(),
)

/** `set_workspace_scope` takes exactly these two words (C6.3). */
enum class WorkspaceAccess { Default, Full }

/** One `/model` preset, as the sheet lists it (C6.2). The gateway marks which one is active. */
data class ChatModelPreset(
    val name: String,
    val model: String,
    val provider: String?,
    val contextWindowTokens: Int?,
    val active: Boolean,
    val isDefault: Boolean,
    /** The window as the core's token copy prints it (`1M`, `200K`): T36. */
    val contextLabel: String? = null,
) {
    /** The second line of the sheet's row: what the preset actually runs. */
    val subtitle: String
        get() = buildList {
            add(if (isDefault) "preset default" else "preset $name")
            contextLabel?.let { add("$it context") }
            provider?.let { add(it) }
        }.joinToString(" \u00b7 ")
}

enum class PaletteMode { Slash, Mention }

/**
 * The three headings a palette sheet can draw, named by the core's own copy keys: the bridge
 * carries the key and the sheet turns it into a string, so no copy is typed in the bridge.
 */
object PaletteGroups {
    const val Commands = "palette.commands"
    const val Sessions = "palette.sessions"
    const val CliApps = "palette.cliApps"
}

/** One row of the slash or mention sheet, with the text it puts in the field. */
data class PaletteRow(
    val id: String,
    val label: String,
    val subtitle: String,
    val group: String,
    val insert: String,
)

data class PaletteSheet(
    val mode: PaletteMode,
    val rows: List<PaletteRow>,
) {
    /** Rows keep their group order; the sheet draws a heading whenever it changes. */
    val grouped: List<Pair<String, List<PaletteRow>>>
        get() {
            val order = mutableListOf<String>()
            val byGroup = mutableMapOf<String, MutableList<PaletteRow>>()
            rows.forEach { row ->
                if (!byGroup.containsKey(row.group)) order += row.group
                byGroup.getOrPut(row.group) { mutableListOf() } += row
            }
            return order.map { it to (byGroup[it] ?: emptyList()) }
        }
}

/** Whether the model sheet's list has arrived, failed, or is still coming. */
enum class PresetState { Loading, Loaded, Failed }

/** The composer's sheets, one value so a screen is a `when` and nothing else. */
enum class ComposerSheet { Model, Access, Usage, Palette, Attach, MessageList, SessionDetails }

/**
 * The bar's session actions, collapsed into one overflow — the design's Android column, because
 * three glyphs beside the drawer button truncate a session title on a 412 dp screen.
 */
enum class SessionAction { MessageList, SessionDetails, NewChat }

/** What the composer's one button is: the artifact's three composer states (`composerState`). */
enum class PrimaryState { Voice, Send, Stop }

/**
 * What the composer needs from one chat (plan.md §7: one command each way). The bridge's
 * `CoreComposerDriver` implements it over a `ChatHandle`; tests answer directly.
 */
interface ComposerDriving {
    /** Send a prompt on an idle chat. False when the gateway did not take it. */
    suspend fun sendPrompt(text: String): Boolean

    /** Queue what was typed while a turn runs (C5.5). */
    fun enqueue(text: String)

    suspend fun stop()

    /** Send the queued prompt now: a steer on the running turn. */
    suspend fun sendQueuedNow()

    fun discardQueued()

    /** False when the `/model` command never reached the socket, so the pick can say so (T39). */
    suspend fun setModelPreset(preset: String): Boolean

    suspend fun setWorkspaceScope(access: WorkspaceAccess)

    /** The slash or mention sheet for this text, or null when neither is open (C6.4). */
    suspend fun loadPalette(text: String, cursor: Int): PaletteSheet?
}

/** A composer with no socket behind it: the screen's state before a chat is opened. */
class EmptyComposerDriver : ComposerDriving {
    override suspend fun sendPrompt(text: String): Boolean = false

    override fun enqueue(text: String) = Unit

    override suspend fun stop() = Unit

    override suspend fun sendQueuedNow() = Unit

    override fun discardQueued() = Unit

    override suspend fun setModelPreset(preset: String): Boolean = false

    override suspend fun setWorkspaceScope(access: WorkspaceAccess) = Unit

    override suspend fun loadPalette(text: String, cursor: Int): PaletteSheet? = null
}
