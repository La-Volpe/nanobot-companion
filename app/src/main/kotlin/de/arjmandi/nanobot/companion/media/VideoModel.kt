package de.arjmandi.nanobot.companion.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What a clip is doing. `Preparing` is the wait for the first frame, `Failed` rests with a retry. */
enum class VideoPhase { Idle, Preparing, Playing, Paused, Failed }

/**
 * The player a clip drives. `VideoView` is the real one — the platform's own, so no Media3 and no
 * dependency — and a fake is the other, which is how the four resting states and the dismissal
 * are tested off-device.
 */
interface VideoTransport {
    /** Load a url and wait for the first frame; throws when the clip cannot be played. */
    suspend fun prepare(url: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()

    val elapsedMs: Long
    val durationMs: Long

    /** Once the last frame has been drawn; the control then offers the clip from the top again. */
    val finished: Boolean
}

/**
 * A clip's state: idle before the sheet exists, preparing while the first frame travels, playing,
 * paused, or failed with a retry. Nothing here draws and nothing here touches a clock — the sheet
 * ticks it, which is why the whole machine runs in a JVM test.
 */
class VideoPlayerModel(private var transport: VideoTransport? = null) {

    private var url: String? = null

    /**
     * Invalidates a prepare that is still in flight. The gateway streams the file back, so the
     * first frame can land after the sheet is gone; a stale generation is dropped instead of
     * starting audio nobody can see or stop.
     */
    private var generation = 0

    /**
     * Where the reader stopped when the app left the foreground. The platform drops its own player
     * with the surface and opens the clip again at the first frame, so this is the only position
     * that survives a trip to the launcher.
     */
    private var backgroundPosition: Long? = null

    var phase: VideoPhase by mutableStateOf(VideoPhase.Idle)
        private set
    var elapsedMs: Long by mutableStateOf(0L)
        private set
    var durationMs: Long by mutableStateOf(0L)
        private set

    /** 0…1, and 0 while nobody has reported a length — never NaN, which no bar can draw. */
    val progress: Float
        get() = if (durationMs > 0L) (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** The view exists only once the sheet is laid out, so the player arrives after the state. */
    fun attach(next: VideoTransport) {
        transport = next
    }

    suspend fun start(target: String) {
        url = target
        // A second call while the first frame is on its way is not a second load.
        if (phase == VideoPhase.Preparing) return
        val transport = transport ?: return
        val mine = ++generation
        phase = VideoPhase.Preparing
        val prepared = runCatching { transport.prepare(target) }.isSuccess
        if (mine != generation) return
        if (!prepared) {
            phase = VideoPhase.Failed
            return
        }
        transport.play()
        phase = VideoPhase.Playing
    }

    suspend fun toggle() {
        val transport = transport ?: return
        when (phase) {
            VideoPhase.Playing -> {
                transport.pause()
                phase = VideoPhase.Paused
            }

            VideoPhase.Paused -> {
                val remembered = backgroundPosition
                backgroundPosition = null
                // A clip that ran to its last frame plays from the top rather than drawing a still.
                if (transport.finished) transport.seekTo(0L) else if (remembered != null) transport.seekTo(remembered)
                transport.play()
                phase = VideoPhase.Playing
            }

            VideoPhase.Failed -> retry()
            // A tap while the first frame is coming is not a second load, and an idle sheet has
            // no clip yet.
            VideoPhase.Idle, VideoPhase.Preparing -> Unit
        }
    }

    suspend fun retry() {
        val target = url ?: return
        phase = VideoPhase.Idle
        start(target)
    }

    /** The sheet reads the player on a tick while it plays, so a paused clip costs nothing. */
    fun tick() {
        val transport = transport ?: return
        elapsedMs = transport.elapsedMs
        durationMs = transport.durationMs
        if (phase == VideoPhase.Playing && transport.finished) phase = VideoPhase.Paused
    }

    /** The app went to the background: hold the frame, and do not resume on the way back. */
    fun pauseForBackground() {
        val transport = transport ?: return
        if (phase != VideoPhase.Playing) return
        transport.pause()
        backgroundPosition = elapsedMs
        phase = VideoPhase.Paused
    }

    /**
     * The sheet went away. `pause` would leave the player holding the file and the sound one
     * resume away, which is the audio-bug shape iOS's `dismantleUIViewController` guards against:
     * the transport is released, and an in-flight prepare is dropped.
     */
    fun release() {
        generation += 1
        transport?.release()
        transport = null
        url = null
        backgroundPosition = null
        phase = VideoPhase.Idle
        elapsedMs = 0L
        durationMs = 0L
    }
}

/** "1:05" for a clip, "1:02:05" once it runs past the hour; never a negative time on screen. */
fun durationLabel(ms: Long): String {
    val total = if (ms > 0L) ms / 1000 else 0L
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "$hours:${minutes.pad()}:${seconds.pad()}"
    } else {
        "$minutes:${seconds.pad()}"
    }
}

private fun Long.pad(): String = if (this < 10) "0$this" else "$this"
