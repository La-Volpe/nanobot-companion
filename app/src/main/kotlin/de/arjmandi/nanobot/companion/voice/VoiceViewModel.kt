package de.arjmandi.nanobot.companion.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hold-to-record and tap-to-dictate (plan.md A6, C6.5), the Android twin of iOS's
 * `VoiceViewModel`.
 *
 * Two gestures, one machine. A hold records until release and is refused under the gateway's own
 * 650 ms floor; a tap starts dictating and the next tap stops it. Both end at the 120 s ceiling,
 * and between release and the transcript the field says "Transcribing…", because that wait is a
 * state the user has to see.
 *
 * The clock is the caller's: the gesture passes `nowMs`, so the floor, the ceiling and the frame's
 * duration are one number and no timer runs inside the model.
 */
class VoiceViewModel(
    private var recording: AudioRecording,
    private var driver: VoiceDriving,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + uiDispatcher()),
) {

    enum class Phase { Idle, Recording, Transcribing }

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set

    var elapsedMs: Long by mutableStateOf(0L)
        private set

    /** The one sentence a gesture produced: why it stopped, or why it was refused. */
    var notice: VoiceNotice? by mutableStateOf(null)
        private set

    var lastTranscript: String? by mutableStateOf(null)
        private set

    /** True while the current recording came from a tap rather than a hold. */
    var tapMode: Boolean by mutableStateOf(false)
        private set

    private var startedAtMs: Long = 0
    private var job: Job? = null

    val transcribing: Boolean get() = phase == Phase.Transcribing

    /** `0:07`, which is the meter's clock. */
    val elapsedLabel: String
        get() {
            val seconds = elapsedMs / 1000
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }

    /** The screen swaps in the chat once a gateway is open, or a scripted pair in a test. */
    fun use(driver: VoiceDriving, recording: AudioRecording? = null) {
        this.driver = driver
        if (recording != null) this.recording = recording
    }

    // The hold gesture.

    fun beginHold(nowMs: Long) {
        if (phase != Phase.Idle) return
        start(nowMs, tap = false)
    }

    suspend fun endHold(nowMs: Long) {
        // A hold that was actually a tap keeps dictating; that is what makes tap-to-dictate work on
        // a short press without a second gesture.
        if (phase != Phase.Recording || tapMode) return
        if (nowMs - startedAtMs < VoiceLimits.FLOOR_MS) {
            cancel(VoiceNotice.TooShort)
            return
        }
        finish(nowMs)
    }

    // The tap gesture.

    suspend fun tap(nowMs: Long) {
        when (phase) {
            Phase.Idle -> start(nowMs, tap = true)
            Phase.Recording -> if (tapMode) finish(nowMs) else endHold(nowMs)
            Phase.Transcribing -> Unit
        }
    }

    /** A press shorter than the tap threshold arms tap-to-dictate and keeps recording. */
    fun armTapMode() {
        if (phase == Phase.Recording) tapMode = true
    }

    /** Called by the meter; the model owns no clock of its own, so the screen passes time. */
    fun tick(nowMs: Long) {
        if (phase != Phase.Recording) return
        elapsedMs = nowMs - startedAtMs
        if (elapsedMs > VoiceLimits.CEILING_MS) cancel(VoiceNotice.TooLong)
    }

    private fun start(nowMs: Long, tap: Boolean) {
        notice = null
        lastTranscript = null
        try {
            recording.start()
        } catch (failure: Throwable) {
            notice = VoiceNotice.MicrophoneUnavailable
            return
        }
        startedAtMs = nowMs
        elapsedMs = 0
        tapMode = tap
        phase = Phase.Recording
    }

    private fun finish(nowMs: Long) {
        val audio = recording.stop()
        if (audio == null || audio.isEmpty()) {
            cancel(VoiceNotice.NothingRecorded)
            return
        }
        val durationMs = nowMs - startedAtMs
        phase = Phase.Transcribing
        // The transcript arrives on the frame's own request id; the screen shows the wait until it
        // does, which is the state the design asks for.
        job = scope.launch {
            val outcome = driver.transcribe(audio, durationMs)
            phase = Phase.Idle
            tapMode = false
            elapsedMs = 0
            when (outcome) {
                is VoiceOutcome.Transcribed -> {
                    lastTranscript = outcome.text
                    notice = null
                }

                // The gateway's own word for it is not a sentence; the screen says what happened.
                is VoiceOutcome.Refused -> notice = VoiceNotice.TranscriptionFailed
            }
        }
    }

    private fun cancel(notice: VoiceNotice) {
        recording.cancel()
        phase = Phase.Idle
        tapMode = false
        elapsedMs = 0
        this.notice = notice
    }

    private companion object {
        fun uiDispatcher() = runCatching { Dispatchers.Main.immediate }.getOrDefault(Dispatchers.Default)
    }
}
