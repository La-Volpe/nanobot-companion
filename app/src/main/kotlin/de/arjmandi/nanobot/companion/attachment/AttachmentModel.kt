package de.arjmandi.nanobot.companion.attachment

/**
 * What the composer may attach, and why it may not (plan.md A6).
 *
 * The numbers are the gateway's (`attachment_policy.py`: max_count 4, 6 MiB each, 24 MiB total,
 * one video ≤ 20 MiB) and arrive on `ready.limits`; the refusals are the core's typed reasons, so
 * this file is the app's shape for them, not a second policy.
 */

/** One file the user picked, before it goes anywhere. */
data class PickedAttachment(val name: String, val mimeType: String, val bytes: ByteArray) {
    val size: Int get() = bytes.size
}

enum class AttachmentKind {
    Image,
    Video,
    File,
    Audio;

    /** The letter on a file card's face: a type, not an illustration (design, `.file-card`). */
    val face: String
        get() = when (this) {
            Image -> "IMG"
            Video -> "VID"
            Audio -> "AUD"
            File -> "FILE"
        }

    companion object {
        fun of(mimeType: String): AttachmentKind = when {
            mimeType.startsWith("image/") -> Image
            mimeType.startsWith("video/") -> Video
            mimeType.startsWith("audio/") -> Audio
            else -> File
        }
    }
}

/** One attachment in the composer, as a card. */
data class PendingAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Int,
    val kind: AttachmentKind,
    /** The gateway's reference, once the upload came back. Null means still in flight. */
    val reference: String? = null,
) {
    val sizeLabel: String
        get() = when {
            size >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", size.toDouble() / (1024 * 1024))
            size >= 1024 -> "${size / 1024} KB"
            else -> "$size B"
        }
}

/** The gateway's own limits, as the composer validates and words them (C3.7). */
data class UploadLimits(
    val maxCount: Int = 4,
    val maxFileBytes: Long = 6L * 1024 * 1024,
    val maxTotalBytes: Long = 24L * 1024 * 1024,
) {
    companion object {
        /** A video has its own ceiling instead of the 6 MiB file one (`validate_upload_batch`). */
        const val MAX_VIDEO_BYTES: Long = 20L * 1024 * 1024
        const val MAX_VIDEOS: Int = 1

        fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"
    }

    /** The attach sheet's second line, which is the real limit rather than a description of it. */
    val sheetSubtitle: String
        get() = "Up to $maxCount files, ${megabytes(maxFileBytes)} each, 1 video"
}

/**
 * Why an attachment was refused, in the app's own words. The bridge maps the core's typed
 * `AttachmentRejection` onto these by its `copyKey`, so the sentences live with the screen and
 * cannot drift from the gateway's rule.
 */
enum class AttachmentReject(val copyKey: String) {
    Empty("attach.empty"),
    TooMany("attach.tooMany"),
    FileTooLarge("attach.fileTooLarge"),
    TotalTooLarge("attach.totalTooLarge"),
    TooManyVideos("attach.tooManyVideos"),
    VideoTooLarge("attach.videoTooLarge"),
    Unsupported("attach.unsupported"),

    /** Not the gateway's refusal: the upload never got there. */
    Unreachable("attach.unreachable"),
    ;

    companion object {
        fun ofCopyKey(key: String): AttachmentReject = entries.firstOrNull { it.copyKey == key } ?: Unreachable
    }
}

/** What one upload came back as: a reference to put on the message, a typed refusal, or a failure. */
sealed interface AttachmentOutcome {
    data class Uploaded(val reference: String) : AttachmentOutcome
    data class Rejected(val reason: AttachmentReject) : AttachmentOutcome
    data object Failed : AttachmentOutcome
}

/** What the composer needs for attachments: one command, over a `ChatHandle.upload`. */
interface AttachmentDriving {
    suspend fun upload(name: String, mimeType: String, bytes: ByteArray): AttachmentOutcome
}

/** An uploader with no gateway behind it: nothing uploads, and it says so rather than pretending. */
class EmptyAttachmentDriver : AttachmentDriving {
    override suspend fun upload(name: String, mimeType: String, bytes: ByteArray): AttachmentOutcome = AttachmentOutcome.Failed
}
