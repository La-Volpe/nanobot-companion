package de.arjmandi.nanobot.companion.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * The record the mock engines write to. Its one job is to survive two threads at once: the
 * engine's, which records, and the test's, which reads. A list without the lock loses entries
 * here — which is what a real suite's shared list did under load.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadLogTest {

    @Test
    fun everyReadIsRecordedEvenWhenTheThreadsOverlap() = runTest {
        val log = ReadLog()
        val writers = (1..8).map { n ->
            launch(Dispatchers.Default) {
                repeat(2_000) { log.record(Read(path = "/api/sessions", query = "n=$n", header = null)) }
            }
        }
        writers.joinAll()

        assertEquals(16_000, log.snapshot.size, "eight threads asked 2000 times each, so nothing may be dropped")
    }

    @Test
    fun aSnapshotIsACopyAndKeepsTheReadsPaired() = runTest {
        val log = ReadLog()
        log.record(Read(path = "/api/settings", query = "", header = "Bearer api"))
        log.record(Read(path = "/api/sessions/x/webui-thread", query = "limit=120&direction=latest", header = "Bearer api"))

        val page = log.snapshot.first { it.path.endsWith("/webui-thread") }
        assertEquals("limit=120&direction=latest", page.query, "the query travels with its own path, not with an index")
        assertEquals(1, log.paths().count { it == "/api/settings" })

        val snapshot = log.snapshot
        log.record(Read(path = "/api/settings", query = "", header = "Bearer api"))
        assertEquals(2, snapshot.size, "a snapshot taken before a later read does not grow under the reader")
    }
}
