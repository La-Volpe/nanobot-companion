package de.arjmandi.nanobot.companion.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The media rows' two resting failures (A9): a picture that will not load says so and offers a
 * retry, and a recording that will not play does the same. A spinner that never stops is the bug
 * these states exist to prevent, so no phase here may still be `Loading` once a call returned.
 *
 * The pixels are the emulator screenshot's job. This is the state machine.
 */
class MediaModelTest {

    /** A stand-in for a decoded picture: the loader's phases do not care what the pixels are. */
    private fun picture(): String = "pixels"

    @Test
    fun aFailedFetchSaysSoInsteadOfDrawingABlankBox() = runTest {
        val loader = MediaImageLoader(fetch = { throw java.io.IOException("HTTP 404") }, decode = { picture() })

        loader.load("http://127.0.0.1:18765/api/media/sig/a.png")

        assertEquals(MediaPhase.Failed, loader.phase)
        assertNull(loader.image)
    }

    @Test
    fun bytesThatAreNotAPictureAreAFailureNotAnEmptyImage() = runTest {
        val loader = MediaImageLoader<String>(fetch = { byteArrayOf(1, 2, 3) }, decode = { null })

        loader.load("http://127.0.0.1:18765/api/media/sig/a.png")

        assertEquals(MediaPhase.Failed, loader.phase, "a decode that returns nothing is the commonest blank box")
    }

    @Test
    fun aLoadThatSucceedsRestsOnReady() = runTest {
        val image = picture()
        val loader = MediaImageLoader(fetch = { byteArrayOf(9) }, decode = { image })

        loader.load("http://127.0.0.1:18765/api/media/sig/a.png")

        assertEquals(MediaPhase.Ready, loader.phase)
        assertEquals("pixels", loader.image)
    }

    @Test
    fun noPhaseIsLoadingOnceTheLoadReturned() = runTest {
        for (works in listOf(true, false)) {
            val image = picture()
            val loader = MediaImageLoader<String>(
                fetch = { byteArrayOf(9) },
                decode = { if (works) image else null },
            )

            loader.load("http://127.0.0.1:18765/api/media/sig/a.png")

            assertFalse(loader.phase == MediaPhase.Loading, "a row may not rest on the spinner")
        }
    }

    @Test
    fun aRetryAsksAgainAndCanSucceed() = runTest {
        var attempts = 0
        val image = picture()
        val loader = MediaImageLoader<String>(
            fetch = { throw java.io.IOException("HTTP 404") },
            decode = { image },
        )
        loader.load("http://127.0.0.1:18765/api/media/sig/a.png")
        assertEquals(MediaPhase.Failed, loader.phase)

        // The same loader, now fetching what it could not before.
        val retried = MediaImageLoader(fetch = { attempts += 1; byteArrayOf(9) }, decode = { image })
        retried.load("http://127.0.0.1:18765/api/media/sig/a.png")
        retried.retry()

        assertEquals(MediaPhase.Ready, retried.phase)
        assertEquals(2, attempts, "the retry is the same request again")
    }

    @Test
    fun aRowThatAlreadyHasItsPictureIsNotFetchedAgain() = runTest {
        var fetches = 0
        val image = picture()
        val loader = MediaImageLoader(fetch = { fetches += 1; byteArrayOf(9) }, decode = { image })

        loader.load("http://127.0.0.1:18765/api/media/sig/a.png")
        loader.load("http://127.0.0.1:18765/api/media/sig/a.png")

        assertEquals(1, fetches, "the list scrolls past the same row constantly")
    }

    @Test
    fun aRowTheGatewayCouldNotSignIsNotLoadable() {
        val row = MediaRow(id = "m1", name = "gone.png", url = null, kind = MediaKindView.Image)

        assertFalse(row.loadable, "an entry with no url is a card that says unavailable")
    }

    @Test
    fun theFirstTapLoadsTheRecordingAndPlaysIt() = runTest {
        val player = AudioPlayerModel()
        val transport = FakeTransport()
        player.attach(transport)

        player.toggle("http://127.0.0.1:18765/api/media/sig/a.wav")

        assertEquals(AudioPhase.Playing, player.phase)
        assertEquals(listOf("http://127.0.0.1:18765/api/media/sig/a.wav"), transport.loaded)
        assertEquals(1, transport.plays)
    }

    @Test
    fun theSecondTapPausesAndTheThirdResumes() = runTest {
        val player = AudioPlayerModel()
        val transport = FakeTransport()
        player.attach(transport)

        player.toggle("http://127.0.0.1:18765/api/media/sig/a.wav")
        player.toggle("http://127.0.0.1:18765/api/media/sig/a.wav")
        assertEquals(AudioPhase.Paused, player.phase)
        assertEquals(1, transport.pauses)

        player.toggle("http://127.0.0.1:18765/api/media/sig/a.wav")
        assertEquals(AudioPhase.Playing, player.phase)
        assertEquals(1, transport.loaded.size, "a resume does not re-read the file")
    }

    @Test
    fun aRecordingThatWillNotPlaySaysSoAndCanBeRetried() = runTest {
        val transport = FakeTransport(failing = true)
        val player = AudioPlayerModel(transport)
        val url = "http://127.0.0.1:18765/api/media/sig/a.wav"

        player.toggle(url)
        assertEquals(AudioPhase.Failed, player.phase, "the row is a sentence with a retry, not a spinner")

        transport.failing = false
        player.retry()

        assertEquals(AudioPhase.Playing, player.phase)
        assertEquals(listOf(url, url), transport.loaded)
    }

    @Test
    fun theProgressLineIsTheElapsedFraction() {
        val transport = FakeTransport().apply {
            elapsed = 5_000
            duration = 20_000
        }
        val player = AudioPlayerModel(transport)

        player.tick()

        assertEquals(5_000L, player.elapsedMs)
        assertTrue(kotlin.math.abs(player.progress - 0.25f) < 0.0001f)
    }

    @Test
    fun aRecordingWithNoKnownLengthDrawsNoLineRatherThanNaN() {
        val player = AudioPlayerModel(FakeTransport())

        player.tick()

        assertEquals(0f, player.progress, "a NaN width crashes the layout of the whole thread")
        assertFalse(player.progress.isNaN())
    }

    private class FakeTransport(var failing: Boolean = false) : AudioTransport {
        val loaded = mutableListOf<String>()
        var plays = 0
        var pauses = 0
        var elapsed = 0L
        var duration = 0L

        override suspend fun load(url: String) {
            loaded += url
            if (failing) throw java.io.IOException("not playable")
        }

        override fun play() {
            plays += 1
        }

        override fun pause() {
            pauses += 1
        }

        override fun release() = Unit

        override val elapsedMs: Long get() = elapsed
        override val durationMs: Long get() = duration
    }
}
