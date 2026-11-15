package de.arjmandi.nanobot.companion.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One media row on a turn (A9): a picture, a clip, a recording or a file card.
 *
 * The three shapes are the WebUI's own (`webui/src/components/AttachmentTile.tsx:33`): an image is
 * inline, a video is a framed player, and everything else is a card. Android draws the picture and
 * the recording with what the platform ships — `BitmapFactory` on a coroutine, `MediaPlayer` — so
 * the app has no image-loading library and no player dependency.
 */
@Composable
fun MediaRowView(row: MediaRow, modifier: Modifier = Modifier) {
    when (row.kind) {
        MediaKindView.Image -> MediaImageView(row, modifier)
        MediaKindView.Video -> MediaVideoRow(row, modifier)
        MediaKindView.Audio -> MediaAudioRow(row, modifier)
        MediaKindView.File -> MediaFileCard(row, modifier)
    }
}

/**
 * An inline picture. Loading is a skeleton at the row's own height, a failure is the sentence and
 * a retry — never a spinner that never stops, and never an empty frame.
 */
@Composable
private fun MediaImageView(row: MediaRow, modifier: Modifier = Modifier) {
    val loader = remember(row.id) {
        MediaImageLoader { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    LaunchedEffect(row.id, row.url) {
        row.url?.let { loader.load(it) }
    }
    val surface = MaterialTheme.colorScheme.surfaceVariant
    when (loader.phase) {
        MediaPhase.Idle, MediaPhase.Loading -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(surface, RoundedCornerShape(Design.Radius.field))
                .testTag("media.loading"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        MediaPhase.Ready -> loader.image?.let { image ->
            Image(
                bitmap = image,
                contentDescription = row.name.ifEmpty { null },
                contentScale = ContentScale.FillWidth,
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("media.image"),
            )
        }

        MediaPhase.Failed -> MediaFailureCard(
            title = stringResource(R.string.media_imageFailed),
            identifier = "media.imageFailed",
            onRetry = { loader.retry() },
            modifier = modifier,
        )
    }
}

/** The sentence and the retry: what a row offers instead of a blank box. */
@Composable
private fun MediaFailureCard(
    title: String,
    identifier: String,
    onRetry: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retry = rememberRetry(onRetry)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Design.minimumTarget)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Design.Radius.field))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .testTag(identifier),
        )
        TextButton(onClick = { retry() }, modifier = Modifier.testTag("$identifier.retry")) {
            Text(stringResource(R.string.media_retry))
        }
    }
}

/** A clip. The row is here so the media is not invisible; playback needs a player this build
 * does not ship, and the screen says so rather than offering a control that does nothing. */
@Composable
private fun MediaVideoRow(row: MediaRow, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Design.minimumTarget)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Design.Radius.field))
            .padding(horizontal = 12.dp)
            .testTag("media.video"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name.ifEmpty { stringResource(R.string.media_video) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.media_videoUnavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A recording, played in place: one control and the line that says how far in it is. */
@Composable
private fun MediaAudioRow(row: MediaRow, modifier: Modifier = Modifier) {
    val player = remember(row.id) { AudioPlayerModel(MediaPlayerTransport()) }
    val retry = rememberRetry { player.retry() }
    androidx.compose.runtime.DisposableEffect(row.id) { onDispose { player.release() } }
    // The row reads the player on a tick while it plays, so a paused recording costs nothing.
    LaunchedEffect(player.phase) {
        while (player.phase == AudioPhase.Playing) {
            player.tick()
            delay(500)
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Design.minimumTarget)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Design.Radius.field))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val scope = rememberCoroutineScope()
        TextButton(
            onClick = { row.url?.let { target -> scope.launch { player.toggle(target) } } },
            enabled = row.loadable,
            modifier = Modifier.testTag("media.audio.toggle"),
        ) {
            Text(
                text = if (player.phase == AudioPhase.Playing) "❚❚" else "▶",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = row.name.ifEmpty { stringResource(R.string.media_audio) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (player.phase == AudioPhase.Failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.media_audioFailed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("media.audioFailed"),
                    )
                    TextButton(onClick = { retry() }, modifier = Modifier.testTag("media.audioFailed.retry")) {
                        Text(stringResource(R.string.media_retry))
                    }
                }
            } else {
                AudioProgressLine(progress = player.progress)
            }
        }
    }
}

@Composable
private fun AudioProgressLine(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .testTag("media.audio.progress"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(MaterialTheme.colorScheme.onSurface, CircleShape),
        )
    }
}

/** The face of something that is not a picture: the kind, the name, or why it cannot be opened. */
@Composable
private fun MediaFileCard(row: MediaRow, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Design.minimumTarget)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Design.Radius.field))
            .padding(horizontal = 12.dp)
            .testTag("media.file"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val face = when (row.kind) {
            MediaKindView.Image -> "IMG"
            MediaKindView.Video -> "VID"
            MediaKindView.Audio -> "AUD"
            MediaKindView.File -> "FILE"
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(face, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name.ifEmpty { stringResource(R.string.media_file) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!row.loadable) {
                Text(
                    text = stringResource(R.string.media_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A retry that does not need a coroutine scope at every call site. */
@Composable
private fun rememberRetry(action: suspend () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return { scope.launch { action() } }
}
