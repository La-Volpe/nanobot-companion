package de.arjmandi.nanobot.companion.automations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design
import de.arjmandi.nanobot.companion.ui.SkeletonRows
import kotlinx.coroutines.launch

/**
 * The automations page, following the design's `data-view="automations"`: a title bar, a
 * "Scheduled" group of cards, the sentence that says where these come from, and one button that
 * asks the agent to create another. A card opens the design's action sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(
    model: AutomationsViewModel,
    onClose: () -> Unit,
    onAskNanobot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var actionsFor by remember { mutableStateOf<AutomationRow?>(null) }
    var confirmingDelete by remember { mutableStateOf<AutomationRow?>(null) }

    LaunchedEffect(Unit) { if (!model.loaded) model.load() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.automations_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("automations.back")) {
                        Icon(
                            painter = painterResource(R.drawable.ic_webui_chevron_right),
                            contentDescription = stringResource(R.string.automations_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            // The WebUI's chevron points forward; Back points the other way.
                            modifier = Modifier.size(16.dp).rotate(180f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.automations_group),
                    fontSize = Design.Interface.microSize,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // The read has not answered: the shape of the cards it will draw, not a blank page
            // that reads as "nothing scheduled" (the WebUI's startup skeleton does the same).
            if (model.page == AutomationsPage.Loading) {
                item {
                    SkeletonRows(
                        count = 3,
                        rowHeight = 72.dp,
                        spacing = 8.dp,
                        modifier = Modifier.padding(vertical = 4.dp),
                        label = stringResource(R.string.automations_loading),
                    )
                }
            }
            if (model.isEmpty) {
                item {
                    Text(
                        text = stringResource(R.string.automations_empty),
                        fontSize = Design.Interface.captionSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 14.dp).testTag("automations.empty"),
                    )
                }
            } else {
                itemsIndexed(model.rows, key = { _, row -> row.id }) { index, row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = if (index == 0) Design.Radius.bubble else 0.dp, topEnd = if (index == 0) Design.Radius.bubble else 0.dp, bottomStart = if (index == model.rows.lastIndex) Design.Radius.bubble else 0.dp, bottomEnd = if (index == model.rows.lastIndex) Design.Radius.bubble else 0.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(topStart = if (index == 0) Design.Radius.bubble else 0.dp, topEnd = if (index == 0) Design.Radius.bubble else 0.dp, bottomStart = if (index == model.rows.lastIndex) Design.Radius.bubble else 0.dp, bottomEnd = if (index == model.rows.lastIndex) Design.Radius.bubble else 0.dp)),
                    ) {
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                        card(row, onClick = { actionsFor = row }, onToggle = { on ->
                            scope.launch { model.setEnabled(row.id, on) }
                        })
                    }
                }
            }
            model.message?.let { message ->
                item {
                    Text(
                        text = message,
                        fontSize = Design.Interface.captionSize,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp).testTag("automations.message"),
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.automations_help),
                    fontSize = Design.Interface.microSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Button(
                    onClick = onAskNanobot,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Design.minimumTarget)
                        .padding(top = 14.dp)
                        .testTag("automations.ask"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_webui_conversation),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.automations_ask), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    actionsFor?.let { row ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(row.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (row.enabled && !row.isSystem) {
                        TextButton(
                            onClick = { actionsFor = null; scope.launch { model.runNow(row.id) } },
                            modifier = Modifier.testTag("automation.run"),
                        ) { Text(stringResource(R.string.automations_run_now)) }
                    }
                    TextButton(
                        onClick = { actionsFor = null; scope.launch { model.setEnabled(row.id, !row.enabled) } },
                        modifier = Modifier.testTag("automation.toggle"),
                    ) {
                        Text(
                            stringResource(
                                if (row.enabled) R.string.automations_pause else R.string.automations_resume,
                            ),
                        )
                    }
                    TextButton(
                        onClick = { actionsFor = null; confirmingDelete = row },
                        modifier = Modifier.testTag("automation.delete"),
                    ) { Text(stringResource(R.string.automations_delete)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionsFor = null }) { Text(stringResource(R.string.automations_cancel)) }
            },
        )
    }

    confirmingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(stringResource(R.string.automations_delete_title)) },
            text = { Text(stringResource(R.string.automations_delete_body, row.name)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmingDelete = null; scope.launch { model.delete(row.id) } },
                ) { Text(stringResource(R.string.automations_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text(stringResource(R.string.automations_cancel)) }
            },
        )
    }
}

/** The design's card: glyph, name, schedule, what it is doing, and either a switch or System. */
@Composable
private fun card(row: AutomationRow, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Design.minimumTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("automation.${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_webui_automations),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.schedule, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.isSystem) {
            Text(
                text = stringResource(R.string.automations_system),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // A switch that the gateway answers with 403 would be a lie; a system job shows the
            // flag instead. Every other row flips here, as the design's card does.
            Switch(checked = row.enabled, onCheckedChange = onToggle)
        }
    }
}
