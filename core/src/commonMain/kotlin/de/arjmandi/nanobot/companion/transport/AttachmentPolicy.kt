package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.AttachmentLimits

/** One picked file, before it is uploaded (C6.6). */
internal data class CandidateFile(val name: String, val mimeType: String, val bytes: Long)

/**
 * The reasons a pick is refused, typed so the platform holds the copy (plan.md §10).
 * Each maps to exactly one sentence in the design's attachment sheet.
 */
internal sealed interface AttachmentRejection {
    data object EmptyFile : AttachmentRejection
    data object TooManyFiles : AttachmentRejection
    data object FileTooLarge : AttachmentRejection
    data object TotalTooLarge : AttachmentRejection
    data object TooManyVideos : AttachmentRejection
    data object VideoTooLarge : AttachmentRejection
    data object UnsupportedType : AttachmentRejection
}

/**
 * Local validation before upload (plan.md C3.7). Numbers and the MIME whitelist are the
 * gateway's own: `channels/websocket/attachment_policy.py` (max_count 4, 6 MiB each,
 * 24 MiB total, one video ≤ 20 MiB) and `ingress_policy.py` for the batch budget.
 */
internal class AttachmentPolicy(private val limits: AttachmentLimits) {

    /**
     * Mirrors `validate_upload_batch`: a video has its own 20 MiB ceiling instead of the
     * 6 MiB file ceiling, and the 24 MiB batch budget counts only non-video bytes.
     */
    fun validate(files: List<CandidateFile>): AttachmentRejection? {
        if (files.isEmpty()) return null
        if (files.any { it.bytes <= 0 }) return AttachmentRejection.EmptyFile
        if (files.any { it.mimeType !in ALLOWED_MIME }) return AttachmentRejection.UnsupportedType

        val videos = files.filter { it.mimeType in VIDEO_MIME_ALLOWED }
        val others = files.filterNot { it.mimeType in VIDEO_MIME_ALLOWED }
        if (others.size > limits.maxCount) return AttachmentRejection.TooManyFiles
        if (videos.size > MAX_VIDEOS_PER_MESSAGE) return AttachmentRejection.TooManyVideos
        if (videos.any { it.bytes > MAX_VIDEO_BYTES }) return AttachmentRejection.VideoTooLarge
        if (others.any { it.bytes > limits.maxFileBytes }) return AttachmentRejection.FileTooLarge
        if (others.sumOf { it.bytes } > limits.maxTotalBytes) return AttachmentRejection.TotalTooLarge
        return null
    }

    private companion object {
        const val MAX_VIDEOS_PER_MESSAGE = 1
        const val MAX_VIDEO_BYTES = 20L * 1024 * 1024

        val IMAGE_MIME_ALLOWED = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        val VIDEO_MIME_ALLOWED = setOf("video/mp4", "video/webm", "video/quicktime")
        val DOCUMENT_MIME_ALLOWED = setOf(
            "application/json", "application/pdf", "application/toml",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-yaml", "application/xhtml+xml", "application/xml", "application/yaml",
            "text/csv", "text/html", "text/markdown", "text/plain", "text/xml", "text/yaml",
        )
        val ALLOWED_MIME = IMAGE_MIME_ALLOWED + VIDEO_MIME_ALLOWED + DOCUMENT_MIME_ALLOWED
    }
}
