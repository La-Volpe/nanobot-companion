package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.media.MediaKindView
import de.arjmandi.nanobot.companion.media.MediaRow
import de.arjmandi.nanobot.companion.media.MediaRowView
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The video row, the part only a composition proves: the tap opens the player over the thread,
 * the app's own controls are what is drawn, a clip that will not play is a sentence with a retry
 * rather than a spinner, and closing returns to the turn.
 *
 * Whether frames move is the emulator run's job; a player view that exists is not playback.
 */
@RunWith(AndroidJUnit4::class)
class MediaVideoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A url the player cannot read. The default is a path that does not exist on the device, which
     * `VideoView` rejects at once instead of waiting out the transport's timeout; the other tests
     * ask for an address that never answers, so the sheet is caught mid-wait.
     */
    private fun clip(name: String = "clip.mp4", url: String = "file:///nonexistent/clip.mp4") =
        MediaRow(id = "m-video", name = name, url = url, kind = MediaKindView.Video)

    private fun show(row: MediaRow) {
        compose.setContent {
            NanobotTheme { MediaRowView(row) }
        }
    }

    @Test
    fun theTapOpensThePlayerWithTheAppsOwnControls() {
        // An address that never answers, so the player stays in its waiting state for the assert.
        show(clip(url = "http://10.255.255.1/clip.mp4"))

        compose.onNodeWithTag("media.video").performClick()

        compose.onNodeWithTag("media.video.sheet").assertIsDisplayed()
        compose.onNodeWithTag("media.video.player").assertIsDisplayed()
        compose.onNodeWithTag("media.video.toggle").assertIsDisplayed()
        compose.onNodeWithTag("media.video.progress").assertIsDisplayed()
        compose.onNodeWithTag("media.video.elapsed").assertIsDisplayed()
        compose.onNodeWithTag("media.video.duration").assertIsDisplayed()
    }

    @Test
    fun aClipThatWillNotPlaySaysSoAndOffersARetry() {
        show(clip())

        compose.onNodeWithTag("media.video").performClick()

        // Bounded: the transport gives up on its own after 15 s, this waits past it.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("media.videoFailed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("media.videoFailed").assertIsDisplayed()
        compose.onNodeWithTag("media.videoFailed.retry").assertIsDisplayed()
        // The sentence rests where the spinner was: a row may not be left waiting for a frame.
        compose.onNodeWithTag("media.video.preparing").assertDoesNotExist()
    }

    @Test
    fun closingThePlayerReturnsToTheThread() {
        show(clip(url = "http://10.255.255.1/clip.mp4"))
        compose.onNodeWithTag("media.video").performClick()
        compose.onNodeWithTag("media.video.sheet").assertIsDisplayed()

        compose.onNodeWithTag("media.video.close").performClick()

        compose.onNodeWithTag("media.video.sheet").assertDoesNotExist()
        compose.onNodeWithTag("media.video").assertIsDisplayed()
    }
}
