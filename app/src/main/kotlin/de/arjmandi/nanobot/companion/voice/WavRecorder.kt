package de.arjmandi.nanobot.companion.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * The microphone, through `AudioRecord`: 16 kHz mono PCM, wrapped in a WAV header on stop
 * because the transcription frame carries WAV bytes and not a raw stream (`transcription_ws.py`).
 *
 * A reader thread drains the buffer while the recording runs, so a long hold does not lose audio to
 * a small kernel buffer; `stop()` joins it and hands back the file.
 */
class WavRecorder : AudioRecording {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var buffer: ByteArrayOutputStream? = null
    private var running = false

    @SuppressLint("MissingPermission")
    override fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) error("no audio input on this device")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            minBuffer * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("the microphone would not open")
        }
        val sink = ByteArrayOutputStream()
        running = true
        record = recorder
        buffer = sink
        recorder.startRecording()
        worker = thread(name = "nanobot-recorder") {
            val chunk = ByteArray(minBuffer)
            while (running) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) sink.write(chunk, 0, read)
            }
        }
    }

    override fun stop(): ByteArray? {
        val sink = buffer
        val recorder = record
        running = false
        worker?.join(500)
        worker = null
        record = null
        buffer = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            recorder.release()
        }
        val pcm = sink?.toByteArray() ?: return null
        return if (pcm.isEmpty()) null else wavFile(pcm)
    }

    override fun cancel() {
        val recorder = record
        running = false
        worker?.join(500)
        worker = null
        record = null
        buffer = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /** A 44-byte RIFF header in front of the PCM, which is what every decoder expects. */
    private fun wavFile(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun int(value: Int) = byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
        )
        fun short(value: Int) = byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte())

        out.write("RIFF".toByteArray())
        out.write(int(36 + pcm.size))
        out.write("WAVEfmt ".toByteArray())
        out.write(int(16))
        out.write(short(1))
        out.write(short(CHANNELS))
        out.write(int(SAMPLE_RATE))
        out.write(int(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE))
        out.write(short(CHANNELS * BYTES_PER_SAMPLE))
        out.write(short(16))
        out.write("data".toByteArray())
        out.write(int(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
    }
}
