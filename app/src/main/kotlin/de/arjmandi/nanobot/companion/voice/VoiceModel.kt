package de.arjmandi.nanobot.companion.voice

/**
 * The microphone, as a seam: a real `AudioRecord` in the app, a fake in a test. The recording
 * comes back as WAV bytes, which is what the gateway's transcription frame carries (C6.5).
 */
interface AudioRecording {
    fun start()

    /** The bytes recorded so far, or null when the microphone produced nothing. */
    fun stop(): ByteArray?

    fun cancel()
}

/** What one transcription came back as (C6.5). */
sealed interface VoiceOutcome {
    data class Transcribed(val text: String) : VoiceOutcome

    /** The gateway's `transcription_error.detail`: its word, not a sentence (P8). */
    data class Refused(val detail: String) : VoiceOutcome
}

interface VoiceDriving {
    suspend fun transcribe(pcm: ByteArray, durationMs: Long): VoiceOutcome
}

/** A transcriber with no gateway behind it. */
class EmptyVoiceDriver : VoiceDriving {
    override suspend fun transcribe(pcm: ByteArray, durationMs: Long): VoiceOutcome = VoiceOutcome.Refused("not_connected")
}

/** The two bounds the gateway enforces (`transcription_ws.py`), in milliseconds. */
object VoiceLimits {
    const val FLOOR_MS: Long = 650
    const val CEILING_MS: Long = 120_000
}

/** The sentences a gesture can produce; the screen turns each into its string. */
enum class VoiceNotice { TooShort, TooLong, MicrophoneUnavailable, NothingRecorded, TranscriptionFailed }
