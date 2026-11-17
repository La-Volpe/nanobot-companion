package de.arjmandi.nanobot.companion.testing

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One HTTP request as the core made it: the path, the query, and the bearer it carried. */
data class Read(public val path: String, public val query: String, public val header: String?) {
    companion object {
        fun of(request: HttpRequestData): Read = Read(
            path = request.url.encodedPath,
            query = request.url.encodedQuery,
            header = request.headers[HttpHeaders.Authorization],
        )
    }
}

/**
 * What the core asked the gateway for. The engine records on its own thread while the test
 * reads on another, and two reads can be in flight at once (`/api/settings` and the thread
 * page), so an unguarded list tears: `HistoryOpenTest` paired a page's path with the settings
 * read's empty query under load. One lock around the append, and an immutable list on the way
 * out, so a reader either sees a read or does not.
 *
 * [changes] is a state flow as well as a list because a test often wants to know that a read
 * *left*, not that its answer came back: a refused settings read fills no state at all.
 */
class ReadLog {

    private val mutex: Mutex = Mutex()
    private val entries: MutableStateFlow<List<Read>> = MutableStateFlow(emptyList())

    /** The reads so far; an immutable list, so a reader cannot see a half-finished append. */
    val snapshot: List<Read> get() = entries.value

    val changes: StateFlow<List<Read>> = entries.asStateFlow()

    suspend fun record(request: HttpRequestData): Unit = record(Read.of(request))

    suspend fun record(read: Read) {
        mutex.withLock { entries.value = entries.value + read }
    }

    fun paths(): List<String> = snapshot.map { it.path }
}
