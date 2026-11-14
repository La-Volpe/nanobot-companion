package de.arjmandi.nanobot.companion.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design

/**
 * The recording meter (artifact: `#voice-meter`): the elapsed clock and a level bar, plus the words
 * for the two gestures. It draws the model's numbers; the clock is the gesture's, not the meter's.
 */
@Composable
fun VoiceMeter(model: VoiceViewModel, modifier: Modifier = Modifier) {
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
            Text(
                text = model.elapsedLabel,
                fontSize = Design.Interface.microSize,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
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
                text = stringResource(if (model.tapMode) R.string.voice_tap_to_stop else R.string.voice_release_to_send),
                fontSize = Design.Interface.microSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** The one sentence a gesture produced, above the field. */
@Composable
fun VoiceNoticeRow(notice: VoiceNotice?, modifier: Modifier = Modifier) {
    val text = when (notice) {
        VoiceNotice.TooShort -> stringResource(R.string.voice_too_short)
        VoiceNotice.TooLong -> stringResource(R.string.voice_too_long)
        VoiceNotice.MicrophoneUnavailable -> stringResource(R.string.voice_microphone)
        VoiceNotice.NothingRecorded -> stringResource(R.string.voice_nothing_recorded)
        VoiceNotice.TranscriptionFailed -> stringResource(R.string.voice_transcription_failed)
        null -> null
    } ?: return
    Text(
        text = text,
        fontSize = Design.Interface.microSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().testTag("voice.notice"),
    )
}
