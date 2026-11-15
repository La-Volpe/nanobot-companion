package de.arjmandi.nanobot.companion.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.chat.ChatTurnView
import de.arjmandi.nanobot.companion.ui.Design
import de.arjmandi.nanobot.companion.ui.SkeletonRows
import kotlinx.coroutines.launch

/**
 * The sheets the composer opens (plan.md A5): model presets, the Default/Full access choice,
 * context usage, the slash or mention palette, the message list and the session details. One
 * `when` on the model's sheet value, so a sheet cannot be half-open — the same shape as iOS's
 * `ComposerSheets`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerSheetHost(
    composer: ComposerViewModel,
    turns: List<ChatTurnView>,
    sessionTitle: String,
    sessionChatId: String?,
    loadPresets: suspend () -> List<ChatModelPreset>?,
    onJumpToTurn: (String) -> Unit,
    onAttachContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sheet = composer.sheet ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { composer.closeSheet() }

    // The model sheet's list is the gateway's, read when the sheet opens rather than at connect.
    LaunchedEffect(sheet) {
        if (sheet == ComposerSheet.Model) composer.loadPresets(loadPresets)
    }

    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        modifier = modifier.testTag("composer.sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when (sheet) {
                ComposerSheet.Model -> {
                    SheetTitle(stringResource(R.string.composer_choose_model))
                    when (composer.presetState) {
                        // The gateway's list is a read of its own: the sheet draws its rows' shape
                        // rather than a list that is empty because nothing has answered yet.
                        PresetState.Loading -> SkeletonRows(
                            count = 3,
                            rowHeight = Design.minimumTarget,
                            modifier = Modifier.padding(vertical = 4.dp),
                            label = stringResource(R.string.composer_loading_presets),
                        )
                        PresetState.Failed -> SheetRow(
                            title = stringResource(R.string.composer_presets_failed),
                            subtitle = stringResource(R.string.composer_presets_failed_body),
                            checked = false,
                            identifier = "composer.model.retry",
                        ) {
                            scope.launch { composer.loadPresets(loadPresets) }
                        }
                        PresetState.Loaded -> composer.presets.forEach { preset ->
                            SheetRow(
                                title = preset.name,
                                subtitle = preset.subtitle,
                                checked = preset.active,
                                identifier = "composer.model.${preset.name}",
                            ) {
                                scope.launch { composer.pick(preset) }
                            }
                        }
                    }
                    SheetRow(
                        title = stringResource(R.string.composer_manage_presets),
                        subtitle = stringResource(R.string.composer_manage_presets_body),
                        checked = false,
                        identifier = "composer.model.manage",
                    ) { close() }
                }

                ComposerSheet.Access -> {
                    SheetTitle(stringResource(R.string.composer_access_title))
                    SheetRow(
                        title = stringResource(R.string.composer_access_default),
                        subtitle = stringResource(R.string.composer_access_default_body),
                        checked = composer.snapshot.access == WorkspaceAccess.Default,
                        identifier = "composer.access.default",
                    ) {
                        scope.launch { composer.pick(WorkspaceAccess.Default) }
                    }
                    SheetRow(
                        title = stringResource(R.string.composer_access_full),
                        subtitle = stringResource(R.string.composer_access_full_body),
                        checked = composer.snapshot.access == WorkspaceAccess.Full,
                        identifier = "composer.access.full",
                    ) {
                        scope.launch { composer.pick(WorkspaceAccess.Full) }
                    }
                }

                ComposerSheet.Usage -> {
                    SheetTitle(stringResource(R.string.composer_usage_title))
                    val percent = composer.usagePercent
                    SheetRow(
                        title = if (percent != null) stringResource(R.string.composer_usage_percent, percent) else stringResource(R.string.composer_usage_none),
                        subtitle = usageSubtitle(composer.snapshot),
                        checked = false,
                        identifier = "composer.usage.row",
                    ) { close() }
                }

                ComposerSheet.Palette -> {
                    SheetTitle(
                        stringResource(
                            if (composer.palette?.mode == PaletteMode.Mention) R.string.composer_mention_title else R.string.composer_palette_title,
                        ),
                    )
                    composer.palette?.grouped?.forEach { (group, rows) ->
                        Text(
                            text = stringResource(paletteGroupLabel(group)).uppercase(),
                            fontSize = Design.Interface.microSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        rows.forEach { row ->
                            SheetRow(title = row.label, subtitle = row.subtitle, checked = false, identifier = "composer.palette.${row.id}") {
                                composer.pick(row)
                            }
                        }
                    }
                }

                ComposerSheet.Attach -> {
                    SheetTitle(stringResource(R.string.composer_attach_title))
                    onAttachContent()
                }

                ComposerSheet.MessageList -> {
                    SheetTitle(stringResource(R.string.composer_message_list))
                    val prompts = turns.mapNotNull { turn -> turn.prompt?.let { turn.id to it } }
                    if (prompts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.composer_message_list_empty),
                            fontSize = Design.Interface.captionSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    prompts.forEachIndexed { index, (turnId, prompt) ->
                        SheetRow(
                            title = prompt,
                            subtitle = stringResource(R.string.composer_message_number, index + 1),
                            checked = false,
                            identifier = "composer.message.$turnId",
                        ) {
                            onJumpToTurn(turnId)
                            close()
                        }
                    }
                }

                ComposerSheet.SessionDetails -> {
                    SheetTitle(stringResource(R.string.composer_session_details))
                    SheetRow(
                        title = sessionTitle,
                        subtitle = sessionChatId ?: stringResource(R.string.composer_session_no_id),
                        checked = false,
                        identifier = "composer.session.title",
                    ) { close() }
                    SheetRow(
                        title = composer.modelLabel,
                        subtitle = composer.snapshot.modelName ?: stringResource(R.string.composer_session_no_model),
                        checked = false,
                        identifier = "composer.session.model",
                    ) { close() }
                }
            }
        }
    }
}

/** The core's copy key for a group heading, as the sheet's own string. */
internal fun paletteGroupLabel(group: String): Int = when (group) {
    PaletteGroups.Sessions -> R.string.palette_group_sessions
    PaletteGroups.CliApps -> R.string.palette_group_cliApps
    else -> R.string.palette_group_commands
}

@Composable
private fun usageSubtitle(snapshot: ComposerSnapshot): String {
    val window = snapshot.contextWindowTokens
        ?: return stringResource(R.string.composer_usage_no_window)
    return stringResource(R.string.composer_usage_tokens, snapshot.usedTokens ?: 0, window)
}
