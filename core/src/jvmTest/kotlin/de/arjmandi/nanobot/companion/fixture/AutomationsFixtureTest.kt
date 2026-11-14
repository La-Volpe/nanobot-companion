package de.arjmandi.nanobot.companion.fixture

import de.arjmandi.nanobot.companion.wire.AutomationsParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C7.2's recorded half. `automations.json` is `/api/webui/automations` from the dev gateway.
 * The *actions* are not recorded: the gateway refuses them over HTTP (405) and every job in
 * this recording is a protected `system_event`, so a live action is only reachable as a 403 —
 * which the contract suite asserts instead (`docs/decisions.md` D31).
 */
class AutomationsFixtureTest {

    private val jobs = AutomationsParser.parse(File(Fixtures.latest, "automations.json").readText())

    @Test
    fun listsJobsFromTheRecordedPayload() {
        assertTrue(jobs.isNotEmpty(), "the dev gateway always runs heartbeat and dream")
        val ids = jobs.map { it.id }
        assertTrue("heartbeat" in ids, "heartbeat is the gateway's own job; got $ids")
        for (job in jobs) {
            assertTrue(job.name.isNotBlank(), "${job.id}: a row needs a name")
            assertTrue(job.schedule.kind.isNotBlank(), "${job.id}: a row needs a schedule kind")
        }
    }

    @Test
    fun everyRecordedJobIsAProtectedSystemAutomation() {
        // This is why the action path is tested against a fake socket and a live 403 rather
        // than a live success: on this gateway nothing is editable.
        assertTrue(jobs.all { it.protected }, "expected only protected jobs, got ${jobs.map { it.id to it.protected }}")
        assertTrue(
            jobs.all { it.kind == "system_event" },
            "a system_event job is refused by every action (ws_http.py:1592)",
        )
    }

    @Test
    fun aScheduledJobCarriesItsNextRun() {
        val heartbeat = jobs.first { it.id == "heartbeat" }
        assertEquals("every", heartbeat.schedule.kind)
        assertEquals(1_800_000L, heartbeat.schedule.everyMs)
        // `next_run_at_ms` is an absolute moment, so the redactor leaves the value alone and it
        // churns between recordings; the assertion is that it parsed, not what it is.
        assertTrue(heartbeat.state.nextRunAtMs != null, "the row shows when it runs next")
        assertEquals(0, heartbeat.state.runs, "heartbeat has no runs in this recording")
    }
}
