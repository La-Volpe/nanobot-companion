package de.arjmandi.nanobot.companion.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design

/**
 * The recording meter (artifact: `#voice-meter`): the elapsed clock and a level bar, plus the words
 * for what the finger has to do, and the one control, once a drag up has locked the recording,
 * that throws it away. It draws the model's numbers; the clock is the gesture's, not the meter's.
 */
@Composable
fun VoiceMeter(
    model: VoiceViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (model.phase) {
        VoiceViewModel.Phase.Recording -> Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Design.Radius.field))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("voice.meter"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The timer the owner could not see: it is the model's elapsed clock, drawn.
            Text(
                text = model.elapsedLabel,
                fontSize = Design.Interface.microSize,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("voice.timer"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(12) { index ->
                    Spacer(
                        modifier = Modifier
                            .width(2.dp)
                            .height((4 + (index % 5) * 2).dp)
                            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(1.dp)),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    if (model.locked) R.string.voice_send_to_finish else R.string.voice_release_to_send,
                ),
                fontSize = Design.Interface.microSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.locked) {
                // A locked recording has no finger on it, so the only way out is a control: the
                // WebUI's own close glyph, at the design's 48 dp (gotcha 25).
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(Design.minimumTarget).testTag("voice.cancel"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_webui_close),
                        contentDescription = stringResource(R.string.voice_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        VoiceViewModel.Phase.Transcribing -> Text(
            text = stringResource(R.string.voice_transcribing),
            fontSize = Design.Interface.microSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().testTag("voice.transcribing"),
        )

        VoiceViewModel.Phase.Idle -> Unit
    }
}

/**
 * The one sentence a gesture produced, above the field, with the one control that puts it away. A
 * gateway with no speech provider answers every recording with a refusal, so a notice nobody can
 * dismiss is a sentence that stays on the composer for the life of the app.
 */
@Composable
fun VoiceNoticeRow(
    notice: VoiceNotice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when (notice) {
        VoiceNotice.HoldToTalk -> stringResource(R.string.voice_hold_to_talk)
        VoiceNotice.TooLong -> stringResource(R.string.voice_too_long)
        VoiceNotice.MicrophoneUnavailable -> stringResource(R.string.voice_microphone)
        VoiceNotice.NothingRecorded -> stringResource(R.string.voice_nothing_recorded)
        VoiceNotice.TranscriptionFailed -> stringResource(R.string.voice_transcription_failed)
        null -> null
    } ?: return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            fontSize = Design.Interface.microSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag("voice.notice"),
        )
        // The WebUI's own close glyph, not a character (gotcha 25).
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(Design.minimumTarget).testTag("voice.notice.dismiss"),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_webui_close),
                contentDescription = stringResource(R.string.voice_notice_dismiss),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
