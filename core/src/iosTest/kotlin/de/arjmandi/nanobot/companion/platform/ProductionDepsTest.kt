package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real iOS composition root has to be usable without an app around it: the clock
 * ticks and the file store lands in Application Support.
 */
class ProductionDepsTest {

    @Test
    fun productionDepsComposeRealSeams() {
        val deps = productionDeps()

        assertTrue(deps.clock.nowMs() > 0, "the wall clock is wired")
        assertEquals("ios", deps.appInfo.platform)
        assertTrue(deps.appInfo.version.isNotBlank(), "Settings has a version to show")

        deps.files.write("production-deps-probe.json", """{"ok":true}""")
        assertEquals("""{"ok":true}""", deps.files.read("production-deps-probe.json"))
        deps.files.delete("production-deps-probe.json")
    }
}
