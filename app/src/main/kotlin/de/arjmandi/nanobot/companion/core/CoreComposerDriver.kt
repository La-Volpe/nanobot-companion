package de.arjmandi.nanobot.companion.core

import de.arjmandi.nanobot.companion.composer.ChatModelPreset
import de.arjmandi.nanobot.companion.composer.ComposerDriving
import de.arjmandi.nanobot.companion.composer.ComposerSnapshot
import de.arjmandi.nanobot.companion.composer.PaletteGroups
import de.arjmandi.nanobot.companion.composer.PaletteMode
import de.arjmandi.nanobot.companion.composer.PaletteRow
import de.arjmandi.nanobot.companion.composer.PaletteSheet
import de.arjmandi.nanobot.companion.composer.WorkspaceAccess
import de.arjmandi.nanobot.companion.facade.ChatHandle
import de.arjmandi.nanobot.companion.facade.ComposerState
import de.arjmandi.nanobot.companion.facade.NanobotCore
import de.arjmandi.nanobot.companion.facade.OutboundDraft
import de.arjmandi.nanobot.companion.facade.PresetResult
import de.arjmandi.nanobot.companion.facade.SendReceipt
import de.arjmandi.nanobot.companion.facade.TokenCount
import de.arjmandi.nanobot.companion.facade.WorkspaceScope
import de.arjmandi.nanobot.companion.projection.PaletteGroupKind
import de.arjmandi.nanobot.companion.projection.PaletteQuery
import de.arjmandi.nanobot.companion.projection.PaletteState
import de.arjmandi.nanobot.companion.wire.ModelPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The composer's commands for one chat (A5), over the facade. Every method forwards and nothing
 * branches here: the core owns the queue, the bounds and the palette rules, and this only names
 * them for a screen that holds no framework type.
 */
class CoreComposerDriver(
    private val handle: ChatHandle,
    private val core: NanobotCore,
    scope: CoroutineScope,
) : ComposerDriving {

    /** The model, the ring and the queue live on the handle, so they arrive as one state. */
    val snapshot: StateFlow<ComposerSnapshot> = handle.composer
        .map { it.toSnapshot() }
        .stateIn(scope, SharingStarted.Eagerly, handle.composer.value.toSnapshot())

    /**
     * A send landed if the receipt says so, or if the thread holds its bubble: the call can fail
     * where the message did not, and a thrown send used to lose what was typed outright (T42).
     */
    override suspend fun sendPrompt(text: String): Boolean {
        val mark = handle.threadMark()
        return try {
            handle.send(OutboundDraft(text = text)) is SendReceipt.Accepted
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            handle.promptLanded(text = text, mark = mark)
        }
    }

    override fun enqueue(text: String) {
        handle.enqueue(text)
    }

    override suspend fun stop() {
        handle.stop()
    }

    override suspend fun sendQueuedNow() {
        handle.sendQueuedNow()
    }

    override fun discardQueued() {
        handle.discardQueued()
    }

    override suspend fun setModelPreset(preset: String): Boolean = try {
        handle.setModelPreset(preset) is PresetResult.Sent
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        false
    }

    override suspend fun setWorkspaceScope(access: WorkspaceAccess) {
        handle.setWorkspaceScope(if (access == WorkspaceAccess.Full) WorkspaceScope.Full else WorkspaceScope.Default)
    }

    /**
     * The two palettes (C6.4): the core reads commands and CLI apps when a sheet opens, then
     * answers with the resolved sheet. A read per open, not a read per keystroke.
     */
    override suspend fun loadPalette(text: String, cursor: Int): PaletteSheet? {
        runCatching { core.refreshPalettes() }
        val state = PaletteQuery.forComposer(core.palette.value, text, cursor) ?: return null
        return state.toSheet()
    }
}

/** `ComposerState` → what the row draws. The ring's two numbers come from the thread's own usage. */
internal fun ComposerState.toSnapshot(): ComposerSnapshot = ComposerSnapshot(
    modelName = modelName,
    modelPreset = modelPreset,
    contextWindowTokens = contextWindowTokens,
    usedTokens = usage?.contextTokens,
    contextPercent = contextPercent,
    access = if (accessMode == WorkspaceScope.Full) WorkspaceAccess.Full else WorkspaceAccess.Default,
    queued = queued,
    limits = limits?.attachments?.toUploadLimits() ?: de.arjmandi.nanobot.companion.attachment.UploadLimits(),
)

internal fun ModelPreset.toPreset(): ChatModelPreset = ChatModelPreset(
    name = name,
    model = model,
    provider = provider,
    contextWindowTokens = contextWindowTokens,
    contextLabel = contextWindowTokens?.let(TokenCount::modelWindow),
    active = active,
    isDefault = isDefault,
)

/**
 * A resolved palette is already the sheet. Only the copy is the platform's, so the group heading
 * travels as the core's copy key and the screen turns it into a string.
 */
internal fun PaletteState.toSheet(): PaletteSheet = PaletteSheet(
    mode = if (mode == de.arjmandi.nanobot.companion.projection.PaletteMode.Slash) PaletteMode.Slash else PaletteMode.Mention,
    rows = groups.flatMap { group ->
        val heading = when (group.kind) {
            PaletteGroupKind.Sessions -> PaletteGroups.Sessions
            PaletteGroupKind.CliApps -> PaletteGroups.CliApps
            PaletteGroupKind.Commands -> PaletteGroups.Commands
        }
        group.items.map { item ->
            PaletteRow(
                id = "$heading-${item.label}",
                label = item.label,
                subtitle = item.subtitle,
                group = heading,
                // A picked row replaces the typed token with its own text plus a space.
                insert = "${item.label} ",
            )
        }
    },
)
