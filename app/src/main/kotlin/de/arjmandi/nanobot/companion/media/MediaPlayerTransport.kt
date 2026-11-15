package de.arjmandi.nanobot.companion.media

import android.media.MediaPlayer
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The platform's own player, so a recording plays without ExoPlayer and without a dependency
 * decision. `prepareAsync` keeps the thread free while the gateway streams the file back over the
 * same signed url the picture loader reads.
 */
class MediaPlayerTransport : AudioTransport {

    private var player: MediaPlayer? = null

    override val elapsedMs: Long
        get() = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    override val durationMs: Long
        // A player that has not measured the file answers -1; a bar cannot be drawn from that.
        get() = runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)

    override suspend fun load(url: String) {
        release()
        val prepared = MediaPlayer()
        player = prepared
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { runCatching { prepared.release() } }
                prepared.setOnPreparedListener { if (continuation.isActive) continuation.resume(Unit) }
                prepared.setOnErrorListener { _, what, extra ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("media error $what/$extra"))
                    }
                    true
                }
                prepared.setDataSource(url)
                prepared.prepareAsync()
            }
        } catch (failure: Throwable) {
            runCatching { prepared.release() }
            player = null
            throw failure
        }
    }

    override fun play() {
        runCatching { player?.start() }
    }

    override fun pause() {
        runCatching { player?.pause() }
    }

    override fun release() {
        runCatching { player?.release() }
        player = null
    }
}
