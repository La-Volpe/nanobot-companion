package de.arjmandi.nanobot.companion.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * A6's two gestures, one machine (the Android twin of iOS's `VoiceViewModelTests`): a hold records
 * until release and is refused under the gateway's own 650 ms floor, a tap starts dictating and the
 * next tap stops it, both end at the 120 s ceiling, and the wait between release and transcript is
 * a state the field shows.
 */
class VoiceViewModelTest {

    private class FakeRecorder : AudioRecording {
        var starts = 0
        var stops = 0
        var cancels = 0
        var bytes: ByteArray? = byteArrayOf(1, 2, 3, 4)

        override fun start() {
            starts += 1
        }

        override fun stop(): ByteArray? {
            stops += 1
            return bytes
        }

        override fun cancel() {
            cancels += 1
        }
    }

    private class FakeVoice(private val gate: CompletableDeferred<String>? = null) : VoiceDriving {
        val durations = mutableListOf<Long>()

        override suspend fun transcribe(pcm: ByteArray, durationMs: Long): VoiceOutcome {
            durations += durationMs
            return VoiceOutcome.Transcribed(gate?.await() ?: "hello from the microphone")
        }
    }

    private fun model(
        recorder: FakeRecorder = FakeRecorder(),
        driver: FakeVoice = FakeVoice(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ) = VoiceViewModel(recorder, driver, scope)

    /** The plan's named red line: release, then "Transcribing…", then the transcript. */
    @Test
    fun transcribingThenTranscript() = runTest {
        val gate = CompletableDeferred<String>()
        val recorder = FakeRecorder()
        val voice = FakeVoice(gate)
        val model = model(recorder, voice)

        model.beginHold(nowMs = 1_000)
        assertEquals(VoiceViewModel.Phase.Recording, model.phase, "the microphone is open")

        // Undispatched, so the release has run up to the gateway call: the wait is on screen.
        val finishing = backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            model.endHold(nowMs = 3_000)
        }
        // The gateway has the recording and has not answered: that wait is on screen.
        assertEquals(VoiceViewModel.Phase.Transcribing, model.phase, "\"Transcribing…\" is a state, not a gap")
        assertTrue(model.transcribing)

        gate.complete("check the write path first")
        finishing.await()

        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
        assertEquals("check the write path first", model.lastTranscript)
        assertEquals(listOf(2_000L), voice.durations, "the gesture's own clock is the duration the gateway is asked about")
        assertEquals(1, recorder.stops)
    }

    @Test
    fun holdUnder650msCancels() = runTest {
        val recorder = FakeRecorder()
        val voice = FakeVoice()
        val model = model(recorder, voice)

        model.beginHold(nowMs = 1_000)
        model.endHold(nowMs = 1_600)

        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
        assertEquals(1, recorder.cancels, "a press that short is not a recording")
        assertTrue(voice.durations.isEmpty(), "so nothing goes to the gateway")
        assertEquals(VoiceNotice.TooShort, model.notice)
    }

    @Test
    fun tapModeRecordsUntilTheNextTap() = runTest {
        val recorder = FakeRecorder()
        val voice = FakeVoice()
        val model = model(recorder, voice)

        model.tap(nowMs = 1_000)
        assertEquals(VoiceViewModel.Phase.Recording, model.phase)
        assertEquals(true, model.tapMode)

        model.tap(nowMs = 1_000 + 5_000)

        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
        assertEquals(false, model.tapMode)
        assertEquals(listOf(5_000L), voice.durations)
    }

    /** A press shorter than the tap threshold arms tap mode and keeps recording (the artifact's rule). */
    @Test
    fun aShortHoldArmsTapMode() = runTest {
        val model = model()

        model.beginHold(nowMs = 1_000)
        model.armTapMode()
        model.endHold(nowMs = 1_200)

        assertEquals(VoiceViewModel.Phase.Recording, model.phase, "a short hold keeps dictating rather than refusing")
        assertTrue(model.tapMode)
    }

    @Test
    fun theCeilingStopsALongRecording() {
        val recorder = FakeRecorder()
        val model = model(recorder)

        model.beginHold(nowMs = 0)
        model.tick(nowMs = 121_000)

        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
        assertEquals(VoiceNotice.TooLong, model.notice)
        assertEquals(1, recorder.cancels, "the gateway's ceiling is enforced on this side")
    }

    /**
     * The refusal is the reader's to put away (T31). The owner's gateway has no speech provider, so
     * every recording comes back refused; a notice with no dismissal is a line that never leaves.
     */
    @Test
    fun aNoticeCanBePutAway() = runTest {
        val model = model()
        model.beginHold(nowMs = 0)
        model.endHold(nowMs = 100)
        assertEquals(VoiceNotice.TooShort, model.notice, "the press was too short, so the composer says why")

        model.dismissNotice()

        assertNull(model.notice, "and the reader can put the sentence away")
    }

    @Test
    fun theMeterCountsUpInMinutesAndSeconds() {
        val model = model()
        model.beginHold(nowMs = 0)
        model.tick(nowMs = 7_000)

        assertEquals("0:07", model.elapsedLabel)

        model.tick(nowMs = 67_000)
        assertEquals("1:07", model.elapsedLabel)
    }

    @Test
    fun aRefusedTranscriptionSaysWhatHappened() = runTest {
        val model = VoiceViewModel(
            FakeRecorder(),
            object : VoiceDriving {
                override suspend fun transcribe(pcm: ByteArray, durationMs: Long) = VoiceOutcome.Refused("not_configured")
            },
            CoroutineScope(Dispatchers.Unconfined),
        )

        model.beginHold(nowMs = 0)
        model.endHold(nowMs = 2_000)

        assertEquals(VoiceNotice.TranscriptionFailed, model.notice, "the gateway's own word is not a sentence")
        assertNull(model.lastTranscript)
    }
}
