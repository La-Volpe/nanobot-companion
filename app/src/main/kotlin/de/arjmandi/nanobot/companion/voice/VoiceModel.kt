package de.arjmandi.nanobot.companion.voice

/**
 * The microphone, as a seam: a real `AudioRecord` in the app, a fake in a test. The recording
 * comes back as WAV bytes, which is what the gateway's transcription frame carries.
 */
interface AudioRecording {
    fun start()

    /** The bytes recorded so far, or null when the microphone produced nothing. */
    fun stop(): ByteArray?

    fun cancel()
}

/** What one transcription came back as. */
sealed interface VoiceOutcome {
    data class Transcribed(val text: String) : VoiceOutcome

    /** The gateway's `transcription_error.detail`: its word, not a sentence. */
    data class Refused(val detail: String) : VoiceOutcome
}

interface VoiceDriving {
    suspend fun transcribe(pcm: ByteArray, durationMs: Long): VoiceOutcome
}

/** A transcriber with no gateway behind it. */
class EmptyVoiceDriver : VoiceDriving {
    override suspend fun transcribe(pcm: ByteArray, durationMs: Long): VoiceOutcome = VoiceOutcome.Refused("not_connected")
}

/**
 * The numbers a recording lives between, in milliseconds.
 *
 * [DISCARD_MS] is the phone's own floor and the one the gesture asks about: a press that short is
 * a tap, so nothing is sent. It sits *above* [FLOOR_MS], the gateway's own refusal threshold
 * (`transcription_ws.py`), so a recording the gateway would reject is never asked about.
 */
object VoiceLimits {
    const val DISCARD_MS: Long = 800
    const val FLOOR_MS: Long = 650
    const val CEILING_MS: Long = 120_000
}

/** The sentences a gesture can produce; the screen turns each into its string. */
enum class VoiceNotice { HoldToTalk, TooLong, MicrophoneUnavailable, NothingRecorded, TranscriptionFailed }

/**
 * The "Hold to talk" hint is shown once and remembered. That is the answer to Android's "tapping
 * does nothing": an explanation, not a second recording mode. The decision to show it is the
 * model's; the remembering is the phone's.
 */
interface VoiceHints {
    fun hasSeenHoldHint(): Boolean

    fun markHoldHintSeen()
}

/** A store that forgets: what a model built in a test gets, and nothing on disk to clean up. */
class InMemoryVoiceHints : VoiceHints {
    private var seen = false

    override fun hasSeenHoldHint(): Boolean = seen

    override fun markHoldHintSeen() {
        seen = true
    }
}
