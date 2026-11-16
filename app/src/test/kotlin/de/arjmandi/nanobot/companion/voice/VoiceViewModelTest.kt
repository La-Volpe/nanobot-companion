package de.arjmandi.nanobot.companion.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/**
 * Hold to talk, one machine (the Android twin of iOS's `VoiceViewModelTests`): the press records
 * the instant it lands, a release under 800 ms is a discard that asks the gateway nothing, a
 * release at or above it transcribes, and a drag up locks the recording so Send and Cancel own it
 * instead of the finger (T48).
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
        driver: VoiceDriving = FakeVoice(),
        hints: VoiceHints = InMemoryVoiceHints(),
    ) = VoiceViewModel(recorder, driver, hints)

    /**
     * The owner's lag, pinned: there is nothing between the finger and the microphone. The old
     * 300 ms tap-mode threshold committed a recording only after a tick of the clock, which is
     * what "it takes a while until the hold is detected" was.
     */
    @Test
    fun recordingStartsOnTouchDown() {
        val recorder = FakeRecorder()
        val model = model(recorder)

        model.beginHold(nowMs = 1_000)

        assertEquals(VoiceViewModel.Phase.Recording, model.phase, "the finger is the start")
        assertTrue(model.recordingNow)
        assertEquals(1, recorder.starts, "and the microphone is open before the finger moves")
        assertEquals(0L, model.elapsedMs)
    }

    /** T48's floor: 799 ms is a tap, so nothing at all goes to the gateway. */
    @Test
    fun aReleaseUnder800msSendsNothing() = runTest {
        val recorder = FakeRecorder()
        val voice = FakeVoice()
        val model = model(recorder, voice)

        model.beginHold(nowMs = 1_000)
        model.release(nowMs = 1_799)

        assertTrue(voice.durations.isEmpty(), "799 ms is a tap, and a tap never reaches the gateway")
        assertEquals(1, recorder.cancels, "the recording is thrown away, not stopped and kept")
        assertEquals(0, recorder.stops)
        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
    }

    @Test
    fun aReleaseOf800msIsTranscribed() = runTest {
        val recorder = FakeRecorder()
        val voice = FakeVoice()
        val model = model(recorder, voice)

        model.beginHold(nowMs = 1_000)
        model.release(nowMs = 1_800)

        assertEquals(listOf(800L), voice.durations, "the gesture's own clock is the duration asked about")
        assertEquals("hello from the microphone", model.lastTranscript)
        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
    }

    /** The phone's floor is above the gateway's, so a recording it would refuse is never asked. */
    @Test
    fun theDiscardFloorSitsAboveTheGateways() {
        assertTrue(
            VoiceLimits.DISCARD_MS > VoiceLimits.FLOOR_MS,
            "800 ms is above the gateway's 650 ms refusal, so the discard is decided here",
        )
    }

    /**
     * A tap explains the gesture once and never again — Android's "tapping does nothing" answered
     * with a sentence instead of a second recording mode.
     */
    @Test
    fun aTapSaysHoldToTalkOnce() = runTest {
        val hints = InMemoryVoiceHints()
        val first = model(hints = hints)

        first.beginHold(nowMs = 0)
        first.release(nowMs = 100)
        assertEquals(VoiceNotice.HoldToTalk, first.notice)

        val second = model(hints = hints)
        second.beginHold(nowMs = 0)
        second.release(nowMs = 100)
        assertNull(second.notice, "the hint is remembered, so the second tap gets no nagging")
        assertEquals(VoiceViewModel.Phase.Idle, second.phase)
    }

    /** Drag up: the finger may leave the screen, and a lift is not a stop. */
    @Test
    fun aDragUpLocksAndSendFinishes() = runTest {
        val recorder = FakeRecorder()
        val voice = FakeVoice()
        val model = model(recorder, voice)

        model.beginHold(nowMs = 0)
        model.lock()
        model.release(nowMs = 3_000)

        assertTrue(model.locked)
        assertEquals(VoiceViewModel.Phase.Recording, model.phase, "a locked recording outlives its gesture")
        assertTrue(voice.durations.isEmpty())

        model.sendLocked(nowMs = 3_000)

        assertEquals(listOf(3_000L), voice.durations, "Send is what finishes a locked recording")
        assertEquals("hello from the microphone", model.lastTranscript)
        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
        assertEquals(false, model.locked)
    }

    /** The cancel target throws the recording away: no request, no sentence, nothing to dismiss. */
    @Test
    fun theCancelTargetSendsNothing() = runTest {
        val recorder = FakeRecorder()
        val voice = FakeVoice()
        val model = model(recorder, voice)

        model.beginHold(nowMs = 0)
        model.lock()
        model.cancelRecording()

        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
        assertEquals(1, recorder.cancels)
        assertEquals(0, recorder.stops)
        assertTrue(voice.durations.isEmpty(), "a cancel asks the gateway nothing")
        assertNull(model.notice, "and it is not a failure, so it says nothing")
    }

    /**
     * The transcribing wait is a visible state, and the arc around the button is the feedback:
     * it advances one step per tick of the caller's clock, never on an animation of its own, so a
     * screen that waits for the frame clock to go idle still gets it.
     */
    @Test
    fun theArcAdvancesOnTicksAndStaysBounded() = runTest {
        val gate = CompletableDeferred<String>()
        val model = model(driver = FakeVoice(gate))

        model.beginHold(nowMs = 0)
        val finishing = backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            model.release(nowMs = 2_000)
        }
        assertTrue(model.transcribing, "the wait is on screen while the gateway works")

        model.tick(nowMs = 2_100)
        val first = model.sweepDegrees
        model.tick(nowMs = 2_200)
        assertTrue(model.sweepDegrees != first, "the arc moves on a tick, not on a frame")
        assertEquals(0L, model.elapsedMs, "and the meter's clock is not the arc's")

        repeat(40) { model.tick(nowMs = 3_000L + it) }
        assertTrue(model.sweepDegrees >= 0f && model.sweepDegrees < 360f, "a bounded angle, not a runaway")

        gate.complete("check the write path first")
        finishing.await()

        assertEquals(VoiceViewModel.Phase.Idle, model.phase)
        assertEquals(0f, model.sweepDegrees, "the arc goes away with the wait")
    }

    /** A press while the machine is busy is not a second gesture: one press, one recording. */
    @Test
    fun aSecondPressDoesNotOpenASecondRecording() {
        val recorder = FakeRecorder()
        val model = model(recorder)

        model.beginHold(nowMs = 0)
        model.beginHold(nowMs = 500)

        assertEquals(1, recorder.starts, "the recorder answers the finger that started it")
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
        model.release(nowMs = 100)
        assertEquals(VoiceNotice.HoldToTalk, model.notice, "the tap explains the gesture")

        model.dismissNotice()

        assertNull(model.notice, "and the reader can put the sentence away")
    }

    @Test
    fun theMeterCountsUpInMinutesAndSeconds() {
        val model = model()
        model.beginHold(nowMs = 0)
        model.tick(nowMs = 7_000)

        assertEquals("0:07", model.elapsedLabel, "the timer the owner could not see")

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
        )

        model.beginHold(nowMs = 0)
        model.release(nowMs = 2_000)

        assertEquals(VoiceNotice.TranscriptionFailed, model.notice, "the gateway's own word is not a sentence")
        assertNull(model.lastTranscript)
    }
}
