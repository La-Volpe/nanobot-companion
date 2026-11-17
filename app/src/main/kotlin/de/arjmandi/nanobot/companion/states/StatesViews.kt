package de.arjmandi.nanobot.companion.states

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design

/**
 * The shell's surfaces: the dot, the banner under the bar, the notice rows in the transcript,
 * the goal strip and the subtask dock above the composer, and the one snackbar. Each composable
 * draws one state the view model owns; none of them decides when it is shown.
 */
@Composable
fun ConnectionDotView(dot: ConnectionDot, modifier: Modifier = Modifier) {
    val colour = dotColour(dot, MaterialTheme.colorScheme.error)
    val label = stringResource(
        when (dot) {
            ConnectionDot.Idle -> R.string.chat_connection_idle
            ConnectionDot.Connecting -> R.string.chat_connection_connecting
            ConnectionDot.Reconnecting -> R.string.chat_connection_reconnecting
            ConnectionDot.Open -> R.string.chat_connection_connected
            ConnectionDot.Offline -> R.string.chat_connection_disconnected
            ConnectionDot.Failed -> R.string.chat_connection_error
        },
    )
    Box(
        modifier = modifier
            .padding(end = 8.dp)
            .size(8.dp)
            .background(colour, CircleShape)
            .semantics { contentDescription = label }
            .testTag("states.dot"),
    )
}

/** The banner under the bar: one line, and "try again" only when it is an honest offer. */
@Composable
fun ConnectionBannerRow(banner: ConnectionBanner, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .heightIn(min = Design.minimumTarget)
            .testTag("states.banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(banner.text.res()),
            fontSize = Design.Interface.captionSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (banner.showsRetry) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("states.retry")) {
                Text(stringResource(R.string.states_try_again), fontSize = Design.Interface.captionSize)
            }
        }
    }
}

/** The banner's sentence, as a resource id, so the copy lives in strings and not here. */
internal fun ConnectionBannerText.res(): Int = when (this) {
    ConnectionBannerText.Connecting -> R.string.states_connecting
    ConnectionBannerText.Reconnecting -> R.string.states_reconnecting
    ConnectionBannerText.Disconnected -> R.string.states_disconnected
    ConnectionBannerText.HandshakeFailed -> R.string.states_handshake_failed
    ConnectionBannerText.AuthFailed -> R.string.connection_authFailed
    ConnectionBannerText.TlsRejected -> R.string.connection_tlsRejected
    ConnectionBannerText.Unreachable -> R.string.connection_unreachable
    ConnectionBannerText.Timeout -> R.string.connection_timeout
    ConnectionBannerText.ProtocolTooNew -> R.string.connection_protocolTooNew
    ConnectionBannerText.MissingCapability -> R.string.connection_missingCapability
    ConnectionBannerText.MissingField -> R.string.connection_missingField
    ConnectionBannerText.ServerError -> R.string.connection_serverError
}

/** One notice row: the sentence, its second line, and its actions. */
@Composable
fun StateRowView(row: StateRow, onAction: (StateActionId) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (row.isError) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(Design.Radius.field),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("states.row.${row.kind.name}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(row.text.res, row.text.argument ?: ""),
            fontSize = Design.Interface.captionSize,
            color = MaterialTheme.colorScheme.onSurface,
        )
        row.detailRes?.let {
            Text(text = stringResource(it), fontSize = Design.Interface.microSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        row.value?.let {
            Text(text = it, fontSize = Design.Interface.microSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.actions.forEach { action ->
                    TextButton(
                        onClick = { onAction(action.identifier) },
                        modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("states.action.${action.identifier.name}"),
                    ) {
                        Text(action.title, fontSize = Design.Interface.captionSize, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** The goal, as a strip above the composer: one line, never a transcript row. */
@Composable
fun GoalStripRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("states.goal"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.states_goal), fontSize = Design.Interface.microSize, fontWeight = FontWeight.SemiBold)
        Text(text = text, fontSize = Design.Interface.microSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The subtask dock: the running subtasks and one stop control each. */
@Composable
fun SubagentDockRow(rows: List<DockRow>, onStop: (String) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().testTag("states.dock"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = Design.minimumTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = row.label, fontSize = Design.Interface.captionSize)
                    Text(text = row.caption, fontSize = Design.Interface.microSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onStop(row.id) }, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("states.dock.stop")) {
                    Text(stringResource(R.string.states_dock_stop), fontSize = Design.Interface.microSize)
                }
            }
        }
    }
}

/** The snackbar's sentence, with its one number where it has one. */
@Composable
fun snackbarText(snackbar: Snackbar): String =
    stringResource(snackbar.text.res, snackbar.secondsRemaining ?: 0)

/** The snackbar's action label, if it has one. */
@Composable
fun snackbarActionLabel(snackbar: Snackbar): String? = when (snackbar.action) {
    StateActionId.Continue -> stringResource(R.string.states_resend)
    StateActionId.Dismiss, null -> null
}

/** The dot's colour for a test that asserts the mapping rather than the pixels. */
internal fun dotColour(dot: ConnectionDot, error: Color): Color = when (dot) {
    ConnectionDot.Open -> Color(0xFF4CAF50)
    ConnectionDot.Connecting, ConnectionDot.Reconnecting -> Color(0xFFFFA726)
    ConnectionDot.Offline, ConnectionDot.Idle -> Color(0xFF9E9E9E)
    ConnectionDot.Failed -> error
}
