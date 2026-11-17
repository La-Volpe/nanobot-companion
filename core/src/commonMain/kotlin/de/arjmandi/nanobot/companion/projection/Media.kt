package de.arjmandi.nanobot.companion.projection

/** What a media row is. Anything the gateway names that this build does not know is a [File]. */
public enum class MediaKind { Image, Video, Audio, File }

/**
 * One picture, clip or recording on a turn. The gateway signs these urls itself
 * (`nanobot/webui/media_api.py:118`), so the path is the authorisation and the client adds
 * nothing to it but the gateway's origin.
 */
public data class MediaAttachment(
    public val id: String,
    public val url: String,
    public val name: String,
    public val kind: MediaKind,
) {
    /**
     * The url a platform loader can open. The gateway sends `/api/media/<sig>/<payload>`, relative
     * to its own origin; an absolute url (or a `data:` one) is already loadable and is left alone.
     */
    public fun absoluteUrl(baseUrl: String): String = when {
        url.isEmpty() -> url
        url.startsWith("http://", ignoreCase = true) -> url
        url.startsWith("https://", ignoreCase = true) -> url
        url.startsWith("data:", ignoreCase = true) -> url
        else -> baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }
}

/**
 * What kind a row is, in the order the WebUI decides it (`webui/src/lib/media.ts:55`): what the
 * name and url say, then the gateway's own word for anything the extension cannot name. The order
 * matters because the gateway has only three words: `media_attachment_kind` (`media_api.py:169`)
 * answers `image`, `video` or `file`, so a recording arrives as `file` with a `.m4a` name. Audio
 * is ours; the WebUI has no player for it, which is why it never needed the word. A type nobody
 * knows is a file row rather than a hole in the thread.
 */
internal fun mediaKindOf(name: String, url: String, declared: String?): MediaKind {
    if (url.startsWith("data:", ignoreCase = true)) {
        val mime = url.substringAfter("data:", "").substringBefore(';').lowercase()
        return when {
            mime.startsWith("image/") -> MediaKind.Image
            mime.startsWith("video/") -> MediaKind.Video
            mime.startsWith("audio/") -> MediaKind.Audio
            else -> MediaKind.File
        }
    }
    val extension = extensionOf(name).ifEmpty { extensionOf(url) }
    return when (extension) {
        in IMAGE_EXTENSIONS -> MediaKind.Image
        in VIDEO_EXTENSIONS -> MediaKind.Video
        in AUDIO_EXTENSIONS -> MediaKind.Audio
        // A name with no extension leaves the gateway's word as the only evidence there is.
        else -> when (declared?.lowercase()) {
            "image" -> MediaKind.Image
            "video" -> MediaKind.Video
            "audio" -> MediaKind.Audio
            else -> MediaKind.File
        }
    }
}

/** The extension of a path with its query and fragment cut off, because a signed url has both. */
private fun extensionOf(value: String): String {
    val path = value.substringBefore('?').substringBefore('#').lowercase()
    val dot = path.lastIndexOf('.')
    return if (dot < 0) "" else path.substring(dot)
}

/** `webui/src/lib/media.ts:3`, plus the two the WebUI never had to name. */
private val IMAGE_EXTENSIONS: Set<String> =
    setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".svg", ".tif", ".tiff", ".heic", ".heif")

private val VIDEO_EXTENSIONS: Set<String> = setOf(".mp4", ".webm", ".mov", ".m4v", ".avi", ".mkv", ".3gp")

private val AUDIO_EXTENSIONS: Set<String> =
    setOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".weba", ".amr")
