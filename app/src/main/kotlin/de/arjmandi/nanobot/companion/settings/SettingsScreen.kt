package de.arjmandi.nanobot.companion.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design
import de.arjmandi.nanobot.companion.ui.SkeletonRows
import kotlinx.coroutines.launch

/**
 * Settings (plan.md A7): sectioned lists and M3 switches, which is the design's Android column.
 * The screen draws the model's rows and calls one command each; every rule is the view model's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    model: SettingsViewModel,
    loadSnapshot: suspend () -> SettingsSnapshot?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirmDisconnect by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { model.refresh(loadSnapshot) }

    // On API 33 an app has to be granted the notification permission before it may post; the
    // screen asks on the tap, and the notifier answers honestly afterwards (D8). A permission the
    // user already gave is not asked for again.
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { model.sendTestNotification() }
    }
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("settings.back")) {
                        Text(text = stringResource(R.string.settings_back), fontSize = Design.Interface.captionSize)
                    }
                },
            )
        },
        modifier = modifier.testTag("settings.screen"),
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Section("Gateway")
            Text(
                text = model.gatewaySubtitle,
                fontSize = Design.Interface.captionSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings.gateway"),
            )
            TextButton(
                onClick = { confirmDisconnect = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget).testTag("settings.disconnect"),
            ) {
                // Left-aligned like the rows around it: the list is a list, not a column of buttons.
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_disconnect), fontSize = Design.Interface.bodySize)
                }
            }

            Section("Overview")
            when (model.overview) {
                // The read has not answered: the shape of the rows it will draw, rather than rows
                // whose every value is blank.
                OverviewState.Loading -> SkeletonRows(
                    count = 4,
                    rowHeight = Design.minimumTarget,
                    modifier = Modifier.padding(vertical = 4.dp),
                    label = stringResource(R.string.settings_overview_loading),
                )
                OverviewState.Failed -> Text(
                    text = stringResource(R.string.settings_overview_failed),
                    fontSize = Design.Interface.captionSize,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp).testTag("settings.overview.failed"),
                )
                OverviewState.Loaded -> Unit
            }
            // The rows exist only once the read answered: a skeleton with the gateway's blank
            // values underneath it is neither one state nor the other.
            if (model.overview == OverviewState.Loaded) model.overviewRows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Design.minimumTarget)
                        .testTag("settings.overview.${row.label}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = row.label, fontSize = Design.Interface.bodySize)
                        row.detail?.let {
                            Text(text = it, fontSize = Design.Interface.microSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    row.value?.let {
                        Text(text = it, fontSize = Design.Interface.captionSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Section("Notifications")
            Text(
                text = model.notificationsCopy,
                fontSize = Design.Interface.microSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings.notificationsCopy"),
            )
            model.notificationToggles.forEach { toggle ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = toggle.title, fontSize = Design.Interface.bodySize, modifier = Modifier.weight(1f))
                    Switch(
                        checked = toggle.isOn,
                        onCheckedChange = { model.setToggle(toggle.title, it) },
                        modifier = Modifier.testTag("settings.toggle.${toggle.title}"),
                    )
                }
            }
            TextButton(
                onClick = {
                    if (needsPermission) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        scope.launch { model.sendTestNotification() }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget).testTag("settings.testNotification"),
            ) {
                Text(stringResource(R.string.settings_test_notification), fontSize = Design.Interface.bodySize)
            }

            Section("Appearance")
            ChoiceRow(
                title = stringResource(R.string.settings_theme),
                options = SettingsTheme.entries.map { it.name },
                selected = model.theme.name,
                onPick = { model.chooseTheme(SettingsTheme.valueOf(it)) },
                tag = "settings.theme",
            )
            ChoiceRow(
                title = stringResource(R.string.settings_reading_size),
                options = ReadingSize.entries.map { it.name },
                selected = model.readingSize.name,
                onPick = { model.chooseReadingSize(ReadingSize.valueOf(it)) },
                tag = "settings.readingSize",
            )

            model.notice?.let { notice ->
                Text(
                    text = stringResource(
                        when (notice) {
                            SettingsNotice.NotificationsOff -> R.string.settings_notifications_off
                            SettingsNotice.TestSent -> R.string.settings_test_sent
                            SettingsNotice.Disconnected -> R.string.settings_disconnected
                        },
                    ),
                    fontSize = Design.Interface.microSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp).testTag("settings.notice"),
                )
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(model.disconnectTitle) },
            text = { Text(model.disconnectMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        model.disconnect()
                        onClose()
                    },
                    modifier = Modifier.testTag("settings.disconnect.confirm"),
                ) {
                    Text(model.disconnectActions.last())
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(model.disconnectActions.first())
                }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = Design.Interface.microSize,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** A three-way choice drawn as one row of text buttons, which is what a segmented control is here. */
@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: String, onPick: (String) -> Unit, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, fontSize = Design.Interface.bodySize, modifier = Modifier.weight(1f))
        options.forEach { option ->
            TextButton(
                onClick = { onPick(option) },
                modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("$tag.$option"),
            ) {
                Text(
                    text = option,
                    fontSize = Design.Interface.captionSize,
                    fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (option == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
