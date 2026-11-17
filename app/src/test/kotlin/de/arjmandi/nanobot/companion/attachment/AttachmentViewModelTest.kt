package de.arjmandi.nanobot.companion.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The composer's attachments (the Android twin of iOS's `AttachmentViewModelTests`): a refused
 * pick never
 * becomes a card, the reason is the core's own typed key, the cards are grouped the way the design
 * draws them (grid, video card, file cards), and the message carries the gateway's references.
 */
class AttachmentViewModelTest {

    private class FakeUploader(private val outcome: AttachmentOutcome) : AttachmentDriving {
        val names = mutableListOf<String>()

        override suspend fun upload(name: String, mimeType: String, bytes: ByteArray): AttachmentOutcome {
            names += name
            return outcome
        }
    }

    private fun picked(name: String = "notes.txt", mime: String = "text/plain", size: Int = 12) =
        PickedAttachment(name = name, mimeType = mime, bytes = ByteArray(size))

    @Test
    fun anUploadedFileBecomesACardWithItsReference() = runTest {
        val model = AttachmentViewModel(FakeUploader(AttachmentOutcome.Uploaded("ref-1")))

        model.add(picked())

        assertEquals(1, model.attachments.size)
        assertEquals("ref-1", model.attachments.single().reference)
        assertEquals(listOf("ref-1"), model.references, "what the send carries is the gateway's reference")
        assertNull(model.lastRejection)
    }

    @Test
    fun aRefusedPickBecomesASentenceAndNoCard() = runTest {
        val model = AttachmentViewModel(FakeUploader(AttachmentOutcome.Rejected(AttachmentReject.TooMany)))

        model.add(picked())

        assertTrue(model.attachments.isEmpty(), "a refused file never becomes a card")
        assertEquals(AttachmentReject.TooMany, model.lastRejection, "the reason is the core's own key, and the screen owns the copy")
    }

    @Test
    fun aFailedUploadSaysSo() = runTest {
        val model = AttachmentViewModel(FakeUploader(AttachmentOutcome.Failed))

        model.add(picked())

        assertEquals(AttachmentReject.Unreachable, model.lastRejection)
    }

    @Test
    fun theCardsAreGroupedByKind() = runTest {
        val model = AttachmentViewModel(FakeUploader(AttachmentOutcome.Uploaded("r")))

        model.add(picked(name = "shot.png", mime = "image/png"))
        model.add(picked(name = "clip.mp4", mime = "video/mp4"))
        model.add(picked(name = "notes.md", mime = "text/markdown"))
        model.add(picked(name = "voice.m4a", mime = "audio/mp4"))

        assertEquals(1, model.images.size)
        assertEquals(1, model.videos.size)
        assertEquals(2, model.files.size, "audio has no player in chat, so it is a file card (design README)")
        assertEquals("IMG", model.images.single().kind.face)
        assertEquals("FILE", model.files.first().kind.face)
    }

    @Test
    fun theLightboxOpensOnThePickedImageAndCloses() = runTest {
        val model = AttachmentViewModel(FakeUploader(AttachmentOutcome.Uploaded("r")))
        model.add(picked(name = "a.png", mime = "image/png"))
        model.add(picked(name = "b.png", mime = "image/png"))

        model.openLightbox(1)
        assertEquals("b.png", model.lightboxImage?.name)

        model.closeLightbox()
        assertNull(model.lightboxImage)
    }

    @Test
    fun removingOneCardLeavesTheRest() = runTest {
        val model = AttachmentViewModel(FakeUploader(AttachmentOutcome.Uploaded("r")))
        model.add(picked(name = "a.txt"))
        model.add(picked(name = "b.txt"))

        model.remove(model.attachments.first().id)

        assertEquals(listOf("b.txt"), model.attachments.map { it.name })
        assertEquals(1, model.references.size)
    }

    @Test
    fun theLimitsAreTheGatewaysNumbers() {
        val limits = UploadLimits(maxCount = 3, maxFileBytes = 5L * 1024 * 1024, maxTotalBytes = 9L * 1024 * 1024)

        assertEquals(3, limits.maxCount)
        assertEquals("5 MiB", UploadLimits.megabytes(limits.maxFileBytes))
        assertEquals("20 MiB", UploadLimits.megabytes(UploadLimits.MAX_VIDEO_BYTES), "a video has its own ceiling")
    }

    @Test
    fun aFileSizeIsWordsNotBytes() {
        assertEquals("6.0 MB", PendingAttachment(id = "1", name = "a", mimeType = "text/plain", size = 6 * 1024 * 1024, kind = AttachmentKind.File).sizeLabel)
        assertEquals("12 KB", PendingAttachment(id = "1", name = "a", mimeType = "text/plain", size = 12 * 1024, kind = AttachmentKind.File).sizeLabel)
        assertEquals("9 B", PendingAttachment(id = "1", name = "a", mimeType = "text/plain", size = 9, kind = AttachmentKind.File).sizeLabel)
    }
}
