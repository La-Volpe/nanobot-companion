package de.arjmandi.nanobot.companion.media

import android.widget.VideoView
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The platform's own player, so a clip plays without Media3 and without a dependency decision.
 * `VideoView` is a `SurfaceView` over `MediaPlayer`, which is why the view is handed in here rather
 * than built: it only exists once the sheet is laid out.
 *
 * `prepare` is bounded. `MediaPlayer` has no timeout of its own, so a gateway that accepts the
 * connection and then says nothing would leave the sheet on its waiting spinner forever; the
 * wait gives up and the sheet draws the sentence and the retry instead.
 */
class VideoViewTransport(private val player: VideoView) : VideoTransport {

    private var completed = false

    override val elapsedMs: Long
        get() = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)

    override val durationMs: Long
        // A player that has not measured the file answers -1; a bar cannot be drawn from that.
        get() = runCatching { player.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)

    override val finished: Boolean get() = completed

    override suspend fun prepare(url: String) {
        val prepared = withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { runCatching { player.stopPlayback() } }
                player.setOnPreparedListener { if (continuation.isActive) continuation.resume(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("video error $what/$extra"))
                    }
                    true
                }
                player.setOnCompletionListener { completed = true }
                runCatching { player.setVideoPath(url) }.onFailure { failure ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException(failure))
                    }
                }
            }
        }
        if (prepared == null) throw IOException("video prepare timed out")
    }

    override fun play() {
        completed = false
        runCatching { player.start() }
    }

    override fun pause() {
        runCatching { player.pause() }
    }

    override fun seekTo(positionMs: Long) {
        completed = false
        runCatching { player.seekTo(positionMs.toInt()) }
    }

    override fun release() {
        // A teardown cannot suspend, and the surface may already be gone: playback stops and the
        // callbacks are cleared so a late first frame has nothing to resume.
        runCatching { player.setOnPreparedListener(null) }
        runCatching { player.setOnErrorListener(null) }
        runCatching { player.setOnCompletionListener(null) }
        runCatching { player.stopPlayback() }
    }

    private companion object {
        /**
         * The same 15 s the image loader allows a connection: long enough for a slow first frame
         * across a home network, short enough that the sheet is never left waiting.
         */
        const val PREPARE_TIMEOUT_MS = 15_000L
    }
}
