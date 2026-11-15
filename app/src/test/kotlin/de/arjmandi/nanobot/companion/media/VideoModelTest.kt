package de.arjmandi.nanobot.companion.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A clip's states (A9's video half): tap the row, the player opens and plays; pause holds the
 * frame; a clip that will not play is a sentence with a retry, never a spinner; and a sheet the
 * reader dismissed stops the sound instead of leaving it in their pocket.
 *
 * The pixels and the frames are the emulator's job. This is the state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoModelTest {

    private val clip = "http://127.0.0.1:18765/api/media/sig/clip.mp4"

    @Test
    fun theFirstOpenPreparesTheClipAndThenPlaysIt() = runTest {
        val transport = FakeTransport()
        val model = VideoPlayerModel(transport)

        model.start(clip)

        assertEquals(VideoPhase.Playing, model.phase)
        assertEquals(listOf(clip), transport.prepared, "the signed url is handed to the player as it stands")
        assertEquals(1, transport.plays, "a prepared clip starts without a second tap")
    }

    @Test
    fun aClipThatWillNotPrepareSaysSoAndNeverSpins() = runTest {
        val transport = FakeTransport(failing = true)
        val model = VideoPlayerModel(transport)

        model.start(clip)

        assertEquals(VideoPhase.Failed, model.phase, "no phase may rest on the spinner once start returned")
        assertEquals(0, transport.plays, "a clip that never prepared is not asked to play")
    }

    @Test
    fun theFailureOffersARetryThatAsksAgain() = runTest {
        val transport = FakeTransport(failing = true)
        val model = VideoPlayerModel(transport)
        model.start(clip)
        assertEquals(VideoPhase.Failed, model.phase)

        transport.failing = false
        model.retry()

        assertEquals(VideoPhase.Playing, model.phase)
        assertEquals(listOf(clip, clip), transport.prepared, "the retry is the same url prepared again")
    }

    @Test
    fun theTogglePausesAndResumesWithoutReloadingTheClip() = runTest {
        val transport = FakeTransport()
        val model = VideoPlayerModel(transport)
        model.start(clip)

        model.toggle()
        assertEquals(VideoPhase.Paused, model.phase)
        assertEquals(1, transport.pauses)

        model.toggle()
        assertEquals(VideoPhase.Playing, model.phase)
        assertEquals(1, transport.prepared.size, "a resume holds the prepared clip, it does not re-read it")
    }

    @Test
    fun dismissingStopsTheClipAndReleasesThePlayer() = runTest {
        val transport = FakeTransport()
        val model = VideoPlayerModel(transport)
        model.start(clip)

        model.release()

        assertEquals(VideoPhase.Idle, model.phase)
        assertEquals(1, transport.releases, "a player nobody owns is released, not paused")
    }

    @Test
    fun aPrepareThatFinishesAfterDismissalDoesNotStartTheSound() = runTest {
        // The gateway streams the file back, so the first frame can land after the sheet is gone.
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport(gate = gate)
        val model = VideoPlayerModel(transport)

        launch { model.start(clip) }
        runCurrent()
        assertEquals(VideoPhase.Preparing, model.phase, "the wait for the first frame is a state, not a void")

        model.release()
        gate.complete(Unit)
        runCurrent()

        assertEquals(VideoPhase.Idle, model.phase)
        assertEquals(0, transport.plays, "a late first frame must not start audio in the reader's pocket")
    }

    @Test
    fun goingToTheBackgroundPausesAndComingBackDoesNotResumeByItself() = runTest {
        val transport = FakeTransport()
        val model = VideoPlayerModel(transport)
        model.start(clip)

        model.pauseForBackground()
        assertEquals(VideoPhase.Paused, model.phase)
        assertEquals(1, transport.pauses)

        model.pauseForBackground()
        assertEquals(1, transport.pauses, "a second pause is not a second call")
        assertEquals(VideoPhase.Paused, model.phase, "the app does not talk over the room it was left in")
    }

    @Test
    fun comingBackFromTheBackgroundResumesWhereTheReaderLeftIt() = runTest {
        // The platform tears its player down with the surface while the app is away and opens the
        // clip again at the first frame, so the position has to be one the model remembered.
        val transport = FakeTransport().apply {
            duration = 20_000
            elapsed = 6_000
        }
        val model = VideoPlayerModel(transport)
        model.start(clip)
        model.tick()

        model.pauseForBackground()
        model.toggle()

        assertEquals(VideoPhase.Playing, model.phase)
        assertEquals(listOf(6_000L), transport.seeks, "the reader is not sent back to the first frame")
        assertEquals(1, transport.prepared.size, "coming back is not a second load")
    }

    @Test
    fun aStartBeforeTheSurfaceExistsDoesNotClaimToBePlaying() = runTest {
        val model = VideoPlayerModel()

        model.start(clip)
        assertEquals(VideoPhase.Idle, model.phase, "no player, no playback: the sheet has not been laid out yet")

        val transport = FakeTransport()
        model.attach(transport)
        model.start(clip)
        assertEquals(VideoPhase.Playing, model.phase)
        assertEquals(listOf(clip), transport.prepared, "the surface arriving is the only thing that loads the clip")
    }

    @Test
    fun theProgressLineIsTheElapsedFraction() {
        val transport = FakeTransport().apply {
            elapsed = 5_000
            duration = 20_000
        }
        val model = VideoPlayerModel(transport)

        model.tick()

        assertEquals(5_000L, model.elapsedMs)
        assertEquals(20_000L, model.durationMs)
        assertTrue(kotlin.math.abs(model.progress - 0.25f) < 0.0001f)
    }

    @Test
    fun aClipWithNoMeasuredLengthDrawsNoLineRatherThanNaN() {
        val model = VideoPlayerModel(FakeTransport())

        model.tick()

        assertEquals(0f, model.progress)
        assertFalse(model.progress.isNaN(), "a NaN width crashes the layout of the whole thread")
    }

    @Test
    fun aClipThatRanToItsEndRestsPausedAndPlaysAgainFromTheTop() = runTest {
        val transport = FakeTransport().apply {
            duration = 13_000
            elapsed = 13_000
        }
        val model = VideoPlayerModel(transport)
        model.start(clip)

        transport.ended = true
        model.tick()
        assertEquals(VideoPhase.Paused, model.phase, "the control stops offering pause on a clip that is over")

        model.toggle()
        assertEquals(VideoPhase.Playing, model.phase)
        assertEquals(listOf(0L), transport.seeks, "the second play starts at the top, not at the last frame")
    }

    @Test
    fun theLengthReadsAsAClipLengthAndNeverAsANegative() {
        assertEquals("0:00", durationLabel(0))
        assertEquals("0:00", durationLabel(-1), "a player that has not measured the file answers -1")
        assertEquals("0:05", durationLabel(5_000))
        assertEquals("1:05", durationLabel(65_000))
        assertEquals("10:05", durationLabel(605_000))
        assertEquals("1:02:05", durationLabel(3_725_000))
    }

    private class FakeTransport(
        var failing: Boolean = false,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : VideoTransport {
        val prepared = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        var plays = 0
        var pauses = 0
        var releases = 0
        var elapsed = 0L
        var duration = 0L
        var ended = false

        override suspend fun prepare(url: String) {
            prepared += url
            gate?.await()
            if (failing) throw java.io.IOException("not playable")
        }

        override fun play() {
            plays += 1
        }

        override fun pause() {
            pauses += 1
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }

        override fun release() {
            releases += 1
        }

        override val elapsedMs: Long get() = elapsed
        override val durationMs: Long get() = duration
        override val finished: Boolean get() = ended
    }
}
