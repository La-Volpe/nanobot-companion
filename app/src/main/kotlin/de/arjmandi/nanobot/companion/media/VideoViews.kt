package de.arjmandi.nanobot.companion.media

import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A clip, played over the thread the way iOS opens `AVPlayerViewController` in a sheet: tap the
 * row, the player takes the screen, closing it returns to the thread underneath.
 *
 * The controls are the app's own, not `MediaController`'s: the platform's transport bar is the
 * Android 2 look the design has no place for, and it would come with a title bar and a scrubber
 * the rest of the app does not use. `VideoView` plays the signed url as it stands — the gateway's
 * media route checks the signature in the path and nothing else (D48), so no header travels here.
 */
@Composable
fun VideoPlayerSheet(url: String, title: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val model = remember(url) { VideoPlayerModel() }
        val scope = rememberCoroutineScope()

        // A player nobody dismissed keeps playing with the sheet gone — the audio bug iOS guards
        // against in `dismantleUIViewController`. Leave the surface, release the player; and hold
        // the frame when the app leaves the foreground rather than talking to the room from a
        // pocket.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    model.pauseForBackground()
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                model.release()
            }
        }

        // The bar reads the player on a tick while it plays, so a held frame costs nothing.
        LaunchedEffect(model.phase) {
            while (model.phase == VideoPhase.Playing) {
                model.tick()
                delay(400)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("media.video.sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.testTag("media.video.close")) {
                    Icon(
                        painter = painterResource(R.drawable.ic_webui_close),
                        contentDescription = stringResource(R.string.media_videoClose),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            val frame = MaterialTheme.colorScheme.surfaceVariant
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(frame),
                contentAlignment = Alignment.Center,
            ) {
                // The surface has to exist before the clip is loaded, so the load starts from the
                // factory rather than from an effect that might run before the view is laid out.
                // A `SurfaceView` measures itself from its layout params, and wrap-content params
                // give it a zero-sized surface: match_parent, then the Compose size decides.
                AndroidView(
                    factory = { context ->
                        VideoView(context).also { view ->
                            view.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            model.attach(VideoViewTransport(view))
                            scope.launch { model.start(url) }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("media.video.player"),
                )
                // Bounded by the transport's own timeout, so this is a wait and not a spinner
                // that never stops.
                if (model.phase == VideoPhase.Preparing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(22.dp)
                            .testTag("media.video.preparing"),
                        strokeWidth = 2.dp,
                    )
                }
            }

            if (model.phase == VideoPhase.Failed) {
                MediaFailureCard(
                    title = stringResource(R.string.media_videoFailed),
                    identifier = "media.videoFailed",
                    onRetry = { model.retry() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                VideoControls(
                    model = model,
                    onToggle = { scope.launch { model.toggle() } },
                )
            }
        }
    }
}

/** The transport bar: the one control, the line that says how far in the clip is, the two times. */
@Composable
private fun VideoControls(model: VideoPlayerModel, onToggle: () -> Unit) {
    val preparing = model.phase == VideoPhase.Preparing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val playing = model.phase == VideoPhase.Playing
            // The glyph is drawn, so the control says what it does in words for a screen reader.
            val label = stringResource(if (playing) R.string.media_videoPause else R.string.media_videoPlay)
            TextButton(
                onClick = onToggle,
                enabled = !preparing,
                modifier = Modifier
                    .size(Design.minimumTarget)
                    .testTag("media.video.toggle"),
            ) {
                Text(
                    text = if (playing) "❚❚" else "▶",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
            PlaybackProgressLine(progress = model.progress, identifier = "media.video.progress")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = durationLabel(model.elapsedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("media.video.elapsed"),
            )
            Text(
                text = durationLabel(model.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("media.video.duration"),
            )
        }
    }
}

/**
 * The line under a control: the recording in a row and the clip in its sheet draw the same bar,
 * so it is one composable and two tags.
 */
@Composable
internal fun PlaybackProgressLine(progress: Float, identifier: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .testTag(identifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(MaterialTheme.colorScheme.onSurface, CircleShape),
        )
    }
}

/** The circle a row puts in front of a clip, the shape iOS's `play.circle` gives it. */
@Composable
internal fun VideoRowFace(unavailable: Boolean) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "▶",
            style = MaterialTheme.typography.bodyMedium,
            color = if (unavailable) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
