package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.AttachmentLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Upload limits are validated before the bytes leave the phone (plan.md C3.7), so a
 * rejection is immediate and typed instead of a 4xx after a 20 MiB upload. The numbers
 * and the MIME whitelist come from the gateway's own attachment policy.
 */
class AttachmentPolicyTest {

    private val policy = AttachmentPolicy(AttachmentLimits())

    private fun file(name: String, mime: String, bytes: Long) = CandidateFile(name, mime, bytes)

    @Test
    fun enforces4x6MiBAnd24MiBTotal() {
        assertNull(
            policy.validate((1..4).map { file("photo$it.png", "image/png", 6L * 1024 * 1024) }),
            "four files of exactly 6 MiB are allowed",
        )

        assertEquals(
            AttachmentRejection.TooManyFiles,
            policy.validate((1..5).map { file("photo$it.png", "image/png", 1_000) }),
        )
        assertEquals(
            AttachmentRejection.FileTooLarge,
            policy.validate(listOf(file("big.png", "image/png", 6L * 1024 * 1024 + 1))),
        )
        // With max_count = 4 and 6 MiB per file the batch budget is exactly reachable, so
        // the total rule is asserted where it can bite: a host that allows more files.
        val generousCount = AttachmentPolicy(AttachmentLimits(maxCount = 8))
        assertEquals(
            AttachmentRejection.TotalTooLarge,
            generousCount.validate((1..5).map { file("photo$it.png", "image/png", 6L * 1024 * 1024) }),
            "five files that each fit still exceed the 24 MiB batch budget",
        )
    }

    @Test
    fun allowsOneVideoUpTo20MiB() {
        assertNull(policy.validate(listOf(file("clip.mp4", "video/mp4", 20L * 1024 * 1024))), "one 20 MiB video is allowed")

        assertEquals(
            AttachmentRejection.TooManyVideos,
            policy.validate(listOf(file("a.mp4", "video/mp4", 1_000), file("b.mp4", "video/mp4", 1_000))),
            "the gateway allows one video per message",
        )
        assertEquals(
            AttachmentRejection.VideoTooLarge,
            policy.validate(listOf(file("clip.mp4", "video/mp4", 20L * 1024 * 1024 + 1))),
        )
    }

    @Test
    fun rejectsSvg() {
        assertEquals(
            AttachmentRejection.UnsupportedType,
            policy.validate(listOf(file("logo.svg", "image/svg+xml", 1_000))),
            "svg is not in the whitelist, so it is refused locally with copy instead of a 415",
        )
        assertEquals(
            AttachmentRejection.UnsupportedType,
            policy.validate(listOf(file("script.sh", "application/x-sh", 1_000))),
        )
    }
}
