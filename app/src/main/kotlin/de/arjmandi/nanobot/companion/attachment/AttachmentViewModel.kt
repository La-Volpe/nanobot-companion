package de.arjmandi.nanobot.companion.attachment

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The composer's attachments (plan.md A6): pick, upload, show as a card, remove. Every limit is the
 * gateway's — the core validates against `ready.limits` before a byte leaves the device and answers
 * with a typed reason — so this only draws the answer and never re-derives a rejection.
 */
class AttachmentViewModel(
    private var driver: AttachmentDriving,
    limits: UploadLimits = UploadLimits(),
) {

    var attachments: List<PendingAttachment> by mutableStateOf(emptyList())
        private set

    /** The reason the last pick was refused, as the core's own key; the screen owns the copy. */
    var lastRejection: AttachmentReject? by mutableStateOf(null)
        private set

    /** The image the lightbox is showing, if it is open. */
    var lightbox: Int? by mutableStateOf(null)
        private set

    var limits: UploadLimits by mutableStateOf(limits)
        private set

    /** Stable, so a card's identity survives the list changing around it. */
    private var nextId = 0

    /** The cards by kind — the grid, the video card and the file cards the design draws. */
    val images: List<PendingAttachment> get() = attachments.filter { it.kind == AttachmentKind.Image }
    val videos: List<PendingAttachment> get() = attachments.filter { it.kind == AttachmentKind.Video }
    val files: List<PendingAttachment>
        get() = attachments.filter { it.kind == AttachmentKind.File || it.kind == AttachmentKind.Audio }

    val lightboxImage: PendingAttachment?
        get() = lightbox?.let { images.getOrNull(it) }

    /** The screen swaps in the chat once a gateway is open, or a scripted uploader in a test. */
    fun use(driver: AttachmentDriving, clear: Boolean) {
        this.driver = driver
        if (clear) {
            attachments = emptyList()
            lightbox = null
            lastRejection = null
        }
    }

    /** The gateway's numbers, as `ready.limits` reports them. */
    fun limits(limits: UploadLimits) {
        this.limits = limits
    }

    /**
     * One picked file: the core validates and uploads; a refusal is a sentence, and a refused file
     * never becomes a card.
     */
    suspend fun add(picked: PickedAttachment) {
        lastRejection = null
        when (val outcome = driver.upload(picked.name, picked.mimeType, picked.bytes)) {
            is AttachmentOutcome.Uploaded -> {
                nextId += 1
                attachments = attachments + PendingAttachment(
                    id = "attach-$nextId",
                    name = picked.name,
                    mimeType = picked.mimeType,
                    size = picked.size,
                    kind = AttachmentKind.of(picked.mimeType),
                    reference = outcome.reference,
                )
            }

            is AttachmentOutcome.Rejected -> lastRejection = outcome.reason
            AttachmentOutcome.Failed -> lastRejection = AttachmentReject.Unreachable
        }
    }

    fun remove(id: String) {
        attachments = attachments.filterNot { it.id == id }
        lightbox = null
    }

    fun clear() {
        attachments = emptyList()
        lightbox = null
    }

    fun clearRejection() {
        lastRejection = null
    }

    fun openLightbox(index: Int) {
        if (index in images.indices) lightbox = index
    }

    fun closeLightbox() {
        lightbox = null
    }

    /** What the send carries: the references the gateway handed back (C6.6). */
    val references: List<String> get() = attachments.mapNotNull { it.reference }
}
