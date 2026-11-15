package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.transport.UPLOAD_MIME_ALLOWED

/**
 * Whether the gateway's upload whitelist takes this MIME type
 * (`channels/websocket/attachment_policy.py`, `UPLOAD_MIME_ALLOWED`).
 *
 * The phone has to ask before it sends, not after. An iPhone photo is HEIC unless the reader
 * switched the camera to "Most Compatible", HEIC is not on that list, and the gateway refuses
 * it — which reads to the reader as "the camera is broken". With this answer the picker
 * re-encodes to a type the gateway does take, and the refusal it cannot avoid is worded with
 * the file's name (`AttachmentRejection`).
 */
public fun gatewayAcceptsUpload(mimeType: String): Boolean = mimeType in UPLOAD_MIME_ALLOWED
