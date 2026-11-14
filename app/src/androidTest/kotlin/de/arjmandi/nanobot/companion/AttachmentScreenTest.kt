package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.attachment.AttachmentDriving
import de.arjmandi.nanobot.companion.attachment.AttachmentOutcome
import de.arjmandi.nanobot.companion.attachment.AttachmentReject
import de.arjmandi.nanobot.companion.attachment.AttachmentStrip
import de.arjmandi.nanobot.companion.attachment.AttachmentViewModel
import de.arjmandi.nanobot.companion.attachment.Lightbox
import de.arjmandi.nanobot.companion.attachment.PickedAttachment
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A6's attachment drawing (the Compose half): the image grid, the video and file cards, the
 * per-reason sentence with the gateway's own numbers in it, and the lightbox on a tap. The limits
 * and the grouping are `AttachmentViewModelTest`'s; this is what only a composition proves.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class OkDriver : AttachmentDriving {
        override suspend fun upload(name: String, mimeType: String, bytes: ByteArray): AttachmentOutcome =
            AttachmentOutcome.Uploaded("ref-$name")
    }

    private class RefusingDriver(private val reason: AttachmentReject) : AttachmentDriving {
        override suspend fun upload(name: String, mimeType: String, bytes: ByteArray): AttachmentOutcome =
            AttachmentOutcome.Rejected(reason)
    }

    private fun picked(name: String, mime: String, size: Int = 8) = PickedAttachment(name, mime, ByteArray(size))

    @Test
    fun theCardsAreDrawnByKind(): Unit = runBlocking {
        val model = AttachmentViewModel(OkDriver())
        model.add(picked("shot.png", "image/png"))
        model.add(picked("clip.mp4", "video/mp4"))
        model.add(picked("notes.md", "text/markdown", 2048))

        compose.setContent { NanobotTheme { AttachmentStrip(model = model) } }

        compose.onNodeWithTag("attach.images").assertIsDisplayed()
        compose.onNodeWithTag("attach.video").assertIsDisplayed()
        compose.onNodeWithTag("attach.file").assertIsDisplayed()
        compose.onNodeWithText("2 KB").assertIsDisplayed()
    }

    @Test
    fun aRefusalCarriesTheGatewaysLimitInItsSentence(): Unit = runBlocking {
        val model = AttachmentViewModel(RefusingDriver(AttachmentReject.FileTooLarge))
        model.add(picked("huge.bin", "application/octet-stream", 1024))

        compose.setContent { NanobotTheme { AttachmentStrip(model = model) } }

        compose.onNodeWithTag("attach.rejection").assertIsDisplayed()
        compose.onNodeWithText("Each file is capped at 6 MiB.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("attach.file").fetchSemanticsNodes().size, "a refused file is never a card")
    }

    @Test
    fun tappingAnImageOpensItsIndex(): Unit = runBlocking {
        val model = AttachmentViewModel(OkDriver())
        model.add(picked("a.png", "image/png"))
        model.add(picked("b.png", "image/png"))

        compose.setContent { NanobotTheme { AttachmentStrip(model = model) } }

        compose.onAllNodesWithTag("attach.image")[1].performClick()
        compose.waitForIdle()
        assertEquals("b.png", model.lightboxImage?.name, "the grid opens the image that was tapped")
    }

    @Test
    fun theLightboxShowsTheImageAndClosesOnATap() {
        val model = AttachmentViewModel(OkDriver())
        runBlocking { model.add(picked("shot.png", "image/png", 4096)) }
        model.openLightbox(0)

        compose.setContent {
            NanobotTheme { Lightbox(image = model.lightboxImage, bitmap = null, onClose = { model.closeLightbox() }) }
        }

        compose.onNodeWithTag("attach.lightbox").assertIsDisplayed()
        compose.onNodeWithText("4 KB").assertIsDisplayed()

        compose.onNodeWithTag("attach.lightbox").performClick()
        compose.waitForIdle()
        assertEquals(null, model.lightbox, "a tap closes the zoom")
    }

    @Test
    fun removingACardLeavesTheOthers(): Unit = runBlocking {
        val model = AttachmentViewModel(OkDriver())
        model.add(picked("a.txt", "text/plain"))
        model.add(picked("b.txt", "text/plain"))

        compose.setContent { NanobotTheme { AttachmentStrip(model = model) } }

        compose.onAllNodesWithTag("attach.remove")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf("b.txt"), model.attachments.map { it.name })
    }
}
