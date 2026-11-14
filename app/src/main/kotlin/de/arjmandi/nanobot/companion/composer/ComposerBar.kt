package de.arjmandi.nanobot.companion.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design

/**
 * The composer's chips and meta row (plan.md A5): the queued prompt above the field, then access,
 * the context ring and the model, then the field with attach on the left and the one button on the
 * right. Every control is at least 48 dp (the design's Android column), and the bar's session
 * actions live in one overflow beside the title rather than in three glyphs.
 */
@Composable
fun ComposerMetaRow(
    composer: ComposerViewModel,
    onOpenAccess: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onOpenAccess, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("composer.access")) {
            Text(
                text = stringResource(
                    if (composer.snapshot.access == WorkspaceAccess.Full) R.string.composer_access_full else R.string.composer_access_default,
                ),
                fontSize = Design.Interface.captionSize,
                color = if (composer.snapshot.access == WorkspaceAccess.Full) Design.brand else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = onOpenUsage, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("composer.usage")) {
                UsageRing(percent = composer.usagePercent)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = composer.usagePercent?.let { "$it%" } ?: "\u2014",
                    fontSize = Design.Interface.microSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenModel, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("composer.model")) {
                Text(
                    text = composer.modelLabel,
                    fontSize = Design.Interface.bodySize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_webui_chevron_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

/** The prompt waiting for the running turn: Send now steers, Delete drops it (design `.queue-chip`). */
@Composable
fun QueueChip(text: String, onSendNow: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Design.Radius.field))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            fontSize = Design.Interface.microSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag("composer.queue"),
        )
        TextButton(onClick = onSendNow, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("composer.queue.sendNow")) {
            Text(stringResource(R.string.composer_queue_send_now), fontSize = Design.Interface.microSize, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("composer.queue.delete")) {
            Text(stringResource(R.string.composer_queue_delete), fontSize = Design.Interface.microSize, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The context ring: `--pct` in the artifact, a stroked circle here. */
@Composable
fun UsageRing(percent: Int?, modifier: Modifier = Modifier) {
    val fraction = (percent ?: 0) / 100f
    val track = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(modifier = modifier.size(13.dp).testTag("composer.ring")) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color = track, radius = size.minDimension / 2, style = stroke)
        if (fraction > 0f) {
            drawArc(
                color = ink,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = stroke,
            )
        }
    }
}

/** The bar's one overflow: the message list, the session details and a new chat, as menu rows. */
@Composable
fun SessionActionsOverflow(composer: ComposerViewModel, expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier.testTag("chat.sessionMenu")) {
        composer.sessionActions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(
                            when (action) {
                                SessionAction.MessageList -> R.string.chat_action_message_list
                                SessionAction.SessionDetails -> R.string.chat_action_session_details
                                SessionAction.NewChat -> R.string.chat_action_new_chat
                            },
                        ),
                    )
                },
                onClick = { composer.choose(action) },
                modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("chat.sessionMenu.${action.name}"),
            )
        }
    }
}

/** The composer's one button: hold to record, tap to send, tap to stop — the model says which. */
@Composable
fun ComposerPrimaryButton(
    state: PrimaryState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (state) {
        PrimaryState.Voice -> "chat.voice"
        PrimaryState.Send -> "chat.send"
        PrimaryState.Stop -> "chat.stop"
    }
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.surface,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier
            .size(Design.minimumTarget)
            .testTag(label),
    ) {
        // The composer's own glyphs from the WebUI (`product-icons.tsx`): voice, send, stop.
        Icon(
            painter = painterResource(
                when (state) {
                    PrimaryState.Voice -> R.drawable.ic_webui_voice
                    PrimaryState.Send -> R.drawable.ic_webui_send
                    PrimaryState.Stop -> R.drawable.ic_webui_stop
                }
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One row of a sheet: label, second line, and a checkmark when it is the current choice. */
@Composable
fun SheetRow(title: String, subtitle: String, checked: Boolean, identifier: String, action: () -> Unit) {
    TextButton(
        onClick = action,
        modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget).testTag(identifier),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, fontSize = Design.Interface.bodySize, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = Design.Interface.microSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_webui_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(Design.Interface.captionSize.value.dp),
            )
        }
    }
}

/** A sheet's title, in one place so every sheet reads the same. */
@Composable
fun SheetTitle(text: String) {
    Text(
        text = text,
        fontSize = Design.Interface.captionSize,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}
