package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.transport.AttachmentPolicy
import de.arjmandi.nanobot.companion.transport.AttachmentRejection
import de.arjmandi.nanobot.companion.transport.CandidateFile
import de.arjmandi.nanobot.companion.wire.AttachmentLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone asks this before it uploads. An iPhone photo is HEIC unless the reader switched
 * the camera to "Most Compatible", and `attachment_policy.py` has no HEIC in its whitelist. So
 * the picker needs the gateway's answer to know it has to re-encode, instead of handing the
 * reader a refusal for a photo they just took.
 */
class UploadFormatsTest {

    @Test
    fun theGatewaysOwnPhotoTypesAreTakenAndHeicIsNot() {
        assertTrue(gatewayAcceptsUpload("image/jpeg"))
        assertTrue(gatewayAcceptsUpload("image/png"))

        assertFalse(gatewayAcceptsUpload("image/heic"), "the iPhone's default photo type is not on the list")
        assertFalse(gatewayAcceptsUpload("image/heif"))
        assertFalse(gatewayAcceptsUpload("image/tiff"))
    }

    /**
     * Two copies of the whitelist would drift, so the phone's answer must be the one
     * `validate_upload_batch` gives: anything it refuses as unsupported is `false` here.
     */
    @Test
    fun theAnswerIsTheOneTheUploadPolicyEnforces() {
        val policy = AttachmentPolicy(AttachmentLimits())
        val types = listOf("image/heic", "image/jpeg", "image/png", "video/quicktime", "video/mp4", "text/markdown", "application/zip")

        for (mime in types) {
            val refused = policy.validate(listOf(CandidateFile("file", mime, 1_000))) == AttachmentRejection.UnsupportedType
            assertEquals(
                gatewayAcceptsUpload(mime),
                !refused,
                "the phone's answer and the upload policy disagree about $mime",
            )
        }
    }
}
