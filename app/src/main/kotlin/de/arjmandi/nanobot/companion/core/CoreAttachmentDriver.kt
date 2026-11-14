package de.arjmandi.nanobot.companion.core

import de.arjmandi.nanobot.companion.attachment.AttachmentDriving
import de.arjmandi.nanobot.companion.attachment.AttachmentOutcome
import de.arjmandi.nanobot.companion.attachment.AttachmentReject
import de.arjmandi.nanobot.companion.attachment.UploadLimits
import de.arjmandi.nanobot.companion.facade.ChatHandle
import de.arjmandi.nanobot.companion.facade.PickedFile
import de.arjmandi.nanobot.companion.facade.TranscriptionResult
import de.arjmandi.nanobot.companion.facade.UploadResult
import de.arjmandi.nanobot.companion.voice.VoiceDriving
import de.arjmandi.nanobot.companion.voice.VoiceOutcome
import de.arjmandi.nanobot.companion.wire.AttachmentLimits

/**
 * One picked file, over the facade (A6). The core validates against `ready.limits` before a byte
 * leaves the device and answers with a typed reason; this only renames the reason by its copy key.
 */
class CoreAttachmentDriver(private val handle: ChatHandle) : AttachmentDriving {

    override suspend fun upload(name: String, mimeType: String, bytes: ByteArray): AttachmentOutcome =
        when (val result = runCatching { handle.upload(PickedFile(name = name, mimeType = mimeType, bytes = bytes)) }.getOrNull()) {
            is UploadResult.Uploaded -> AttachmentOutcome.Uploaded(result.reference)
            is UploadResult.Rejected -> AttachmentOutcome.Rejected(AttachmentReject.ofCopyKey(result.reason.copyKey))
            else -> AttachmentOutcome.Failed
        }
}

/** One recording, over the facade (A6): the core owns both bounds and the round trip (C6.5). */
class CoreVoiceDriver(private val handle: ChatHandle) : VoiceDriving {

    override suspend fun transcribe(pcm: ByteArray, durationMs: Long): VoiceOutcome =
        when (val result = runCatching { handle.transcribe(pcm = pcm, durationMs = durationMs) }.getOrNull()) {
            is TranscriptionResult.Transcribed -> VoiceOutcome.Transcribed(result.text)
            is TranscriptionResult.Rejected -> VoiceOutcome.Refused(result.reason.copyKey)
            is TranscriptionResult.Failed -> VoiceOutcome.Refused(result.detail)
            else -> VoiceOutcome.Refused("not_connected")
        }
}

/** The gateway's attachment numbers, as the composer words them (`ready.limits`, C3.7). */
internal fun AttachmentLimits.toUploadLimits(): UploadLimits = UploadLimits(
    maxCount = maxCount,
    maxFileBytes = maxFileBytes,
    maxTotalBytes = maxTotalBytes,
)
