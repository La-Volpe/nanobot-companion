package de.arjmandi.nanobot.companion.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Hold to talk, the Android twin of iOS's `VoiceViewModel`.
 *
 * The model is one machine with three states and one rule of thumb: the finger is the start. A
 * press records the instant it lands (no threshold, no commit step), because the old 300 ms
 * tap-mode wait read as lag on the owner's phone. What a release then means depends only on
 * how long the press lasted: under [VoiceLimits.DISCARD_MS] it was a tap, the recording is thrown
 * away with no request, and the first tap explains the gesture; at or above it the recording goes
 * to the gateway and the transcript lands in the field. A drag up in between locks the recording so
 * the finger may leave the screen: the button becomes Send, and a cancel target throws the
 * recording away.
 *
 * The clock is the caller's: the gesture passes `nowMs`, so the floor, the ceiling and the frame's
 * duration are one number and no timer runs inside the model. That includes the transcribing arc:
 * it advances on [tick], never on an animation, so a screen that waits for quiet still gets it.
 */
class VoiceViewModel(
    private var recording: AudioRecording,
    private var driver: VoiceDriving,
    private val hints: VoiceHints = InMemoryVoiceHints(),
) {

    enum class Phase { Idle, Recording, Transcribing }

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set

    var elapsedMs: Long by mutableStateOf(0L)
        private set

    /** True once a drag up handed the recording to the button; the finger may leave the screen. */
    var locked: Boolean by mutableStateOf(false)
        private set

    /** The one sentence a gesture produced: why it stopped, or why it was refused. */
    var notice: VoiceNotice? by mutableStateOf(null)
        private set

    var lastTranscript: String? by mutableStateOf(null)
        private set

    /**
     * The transcribing arc's angle. It moves only on [tick], one step per tick: a determinate
     * sweep, because an endless animation keeps the frame clock busy and every test that waits
     * for idle waits forever (`Skeleton` is static for exactly this reason).
     */
    var sweepDegrees: Float by mutableStateOf(0f)
        private set

    private var startedAtMs: Long = 0

    val transcribing: Boolean get() = phase == Phase.Transcribing

    val recordingNow: Boolean get() = phase == Phase.Recording

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

    /** A notice is the reader's to put away: it is a line on the composer, not an alert. */
    fun dismissNotice() {
        notice = null
    }

    /**
     * The finger landed: record now. A second press while a recording is in flight is not a
     * gesture the machine has, so it is ignored rather than allowed to steal the first one.
     */
    fun beginHold(nowMs: Long) {
        if (phase != Phase.Idle) return
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
        locked = false
        phase = Phase.Recording
    }

    /**
     * The finger lifted. Under the discard floor the press was a tap: the recording is thrown away
     * and the gateway is never asked. At or above it, the recording is the question.
     */
    suspend fun release(nowMs: Long) {
        if (phase != Phase.Recording) return
        // A locked recording outlived its gesture: the button owns it now, and a lift is not a stop.
        if (locked) return
        if (nowMs - startedAtMs < VoiceLimits.DISCARD_MS) {
            discardAsTap()
            return
        }
        finish(nowMs)
    }

    /** A drag up: the recording carries on with no finger on it, and the button becomes Send. */
    fun lock() {
        if (phase == Phase.Recording) locked = true
    }

    /** The Send side of a locked recording: tap it to finish and transcribe. */
    suspend fun sendLocked(nowMs: Long) {
        if (phase != Phase.Recording || !locked) return
        finish(nowMs)
    }

    /** The cancel target: the recording is thrown away without a request and without a sentence. */
    fun cancelRecording() {
        if (phase != Phase.Recording) return
        recording.cancel()
        reset()
    }

    /** Called by the meter; the model owns no clock of its own, so the screen passes time. */
    fun tick(nowMs: Long) {
        when (phase) {
            Phase.Recording -> {
                elapsedMs = nowMs - startedAtMs
                if (elapsedMs > VoiceLimits.CEILING_MS) cancel(VoiceNotice.TooLong)
            }

            Phase.Transcribing -> sweepDegrees = (sweepDegrees + SWEEP_STEP_DEGREES) % 360f
            Phase.Idle -> Unit
        }
    }

    /**
     * A tap is discarded silently, and the first one explains the gesture, once ever, because a
     * hint that keeps coming back is a nag. Persisting the "seen" flag is [VoiceHints]'s job.
     */
    private fun discardAsTap() {
        recording.cancel()
        reset()
        if (!hints.hasSeenHoldHint()) {
            hints.markHoldHintSeen()
            notice = VoiceNotice.HoldToTalk
        }
    }

    private suspend fun finish(nowMs: Long) {
        val audio = recording.stop()
        val durationMs = nowMs - startedAtMs
        reset()
        if (audio == null || audio.isEmpty()) {
            notice = VoiceNotice.NothingRecorded
            return
        }
        phase = Phase.Transcribing
        // The transcript arrives on the frame's own request id, so the wait is a state the screen
        // shows rather than a gap: the button is inactive and the arc turns while we ask.
        val outcome = driver.transcribe(audio, durationMs)
        phase = Phase.Idle
        sweepDegrees = 0f
        when (outcome) {
            is VoiceOutcome.Transcribed -> {
                lastTranscript = outcome.text
                notice = null
            }

            // The gateway's own word for it is not a sentence; the screen says what happened.
            is VoiceOutcome.Refused -> notice = VoiceNotice.TranscriptionFailed
        }
    }

    private fun cancel(notice: VoiceNotice) {
        recording.cancel()
        reset()
        this.notice = notice
    }

    private fun reset() {
        phase = Phase.Idle
        locked = false
        elapsedMs = 0
        sweepDegrees = 0f
    }

    private companion object {
        /** 12° a tick at 100 ms is a light circle, not a spinner. */
        const val SWEEP_STEP_DEGREES: Float = 12f
    }
}
