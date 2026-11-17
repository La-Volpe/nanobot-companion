package de.arjmandi.nanobot.companion.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a media row is, as the core's `MediaKind` names it. */
enum class MediaKindView { Image, Video, Audio, File }

/**
 * One picture, clip or recording on a turn. The url is already absolute: the core resolved the
 * path the gateway signed against the gateway's own origin, so a row is a value the screen draws
 * and nothing in the UI composes urls.
 */
data class MediaRow(
    val id: String,
    val name: String,
    val url: String?,
    val kind: MediaKindView,
) {
    /** An entry the gateway could not sign is a card that says so, not an image to wait for. */
    val loadable: Boolean get() = !url.isNullOrBlank()
}

/** What a row's bytes are doing. `Failed` is a resting state with a retry, never a spinner. */
enum class MediaPhase { Idle, Loading, Ready, Failed }

/**
 * One row's picture: fetched once, decoded off the main thread, and kept.
 *
 * `fetch` and `decode` are the seams, and the value is generic because the state machine is not
 * about pixels: the app decodes a `Bitmap`, a host test decodes a word, and both go through the
 * same four phases. That is how "the row says so instead of drawing a blank box" and "a retry
 * asks again" are proved without a device.
 *
 * No bearer header goes on this request. The gateway's media route checks the signature in the
 * path (`nanobot/webui/media_api.py:207`) and nothing else, so adding the api token here would be
 * plumbing the endpoint does not want.
 */
class MediaImageLoader<T>(
    private val fetch: suspend (String) -> ByteArray = ::readBytes,
    private val decode: (ByteArray) -> T?,
) {
    var phase: MediaPhase by mutableStateOf(MediaPhase.Idle)
        private set
    var image: T? by mutableStateOf(null)
        private set

    private var url: String? = null

    /**
     * Load once. A row that already holds its picture is not fetched again: the thread scrolls
     * past the same rows constantly and a decode per recomposition stutters the list.
     */
    suspend fun load(url: String) {
        this.url = url
        if (phase != MediaPhase.Idle) return
        run(url)
    }

    suspend fun retry() {
        val target = url ?: return
        phase = MediaPhase.Idle
        image = null
        run(target)
    }

    private suspend fun run(target: String) {
        phase = MediaPhase.Loading
        val bytes = runCatching { fetch(target) }.getOrNull()
        val decoded = bytes?.let { runCatching { decode(it) }.getOrNull() }
        if (decoded == null) {
            image = null
            phase = MediaPhase.Failed
        } else {
            image = decoded
            phase = MediaPhase.Ready
        }
    }

    companion object {
        /** The transport, on the JDK's own client: no third-party loader, no new dependency. */
        suspend fun read(url: String): ByteArray = readBytes(url)
    }
}

/** The JDK's own client: no third-party image loader, no new dependency. */
internal suspend fun readBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("HTTP $status")
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

/** What an audio row is doing (the same five states as iOS's `AudioPhase`). */
enum class AudioPhase { Idle, Loading, Playing, Paused, Failed }

/**
 * The player an audio row drives. `MediaPlayer` is the real one (the platform's own, so no
 * ExoPlayer and no dependency), and a fake is the other, which is how the play/pause, the retry
 * and the progress line are tested off-device.
 */
interface AudioTransport {
    /** Prepare a file; throws when it cannot be played. */
    suspend fun load(url: String)
    fun play()
    fun pause()
    fun release()

    val elapsedMs: Long
    val durationMs: Long
}

/** An audio row's state: one control, and the line that says how far in the recording is. */
class AudioPlayerModel(
    private val transport: AudioTransport? = null,
) {
    private var real: AudioTransport? = transport
    private var url: String? = null

    var phase: AudioPhase by mutableStateOf(AudioPhase.Idle)
        private set
    var elapsedMs: Long by mutableStateOf(0L)
        private set
    var durationMs: Long by mutableStateOf(0L)
        private set

    /** 0…1, and 0 while nobody has reported a length, never NaN, which no bar can draw. */
    val progress: Float
        get() = if (durationMs > 0L) (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /**
     * `MediaPlayerTransport` cannot be built on the JVM, so the app passes a factory and a test
     * passes its fake: the state machine never asks which one it has.
     */
    fun attach(transport: AudioTransport) {
        real = transport
    }

    suspend fun toggle(target: String) {
        url = target
        val transport = real ?: return
        when (phase) {
            AudioPhase.Playing -> {
                transport.pause()
                phase = AudioPhase.Paused
            }

            AudioPhase.Paused -> {
                transport.play()
                phase = AudioPhase.Playing
            }

            AudioPhase.Idle, AudioPhase.Failed -> start(target, transport)
            // A second tap while the file is still coming is not a second load.
            AudioPhase.Loading -> Unit
        }
    }

    suspend fun retry() {
        val target = url ?: return
        val transport = real ?: return
        start(target, transport)
    }

    private suspend fun start(target: String, transport: AudioTransport) {
        phase = AudioPhase.Loading
        if (runCatching { transport.load(target) }.isFailure) {
            phase = AudioPhase.Failed
            return
        }
        transport.play()
        phase = AudioPhase.Playing
    }

    /** The row reads the transport on a tick, so a paused recording costs nothing. */
    fun tick() {
        val transport = real ?: return
        elapsedMs = transport.elapsedMs
        durationMs = transport.durationMs
    }

    fun release() {
        real?.release()
        phase = AudioPhase.Idle
    }
}
