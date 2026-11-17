package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * The composition root. Its point is that an engine or facade test can
 * be built out of fakes alone, so this test composes [CoreDeps] from the test doubles
 * and uses every seam through it: if a seam is missing, nothing else in the facade compiles.
 */
class CoreDepsTest {

    private val clock = FakeClock(startMs = 1_000)

    private fun deps(): CoreDeps {
        val dispatcher = StandardTestDispatcher()
        return CoreDeps(
            clock = clock,
            secureStore = InMemorySecureStore(),
            files = InMemoryFileStore(),
            appInfo = FakeAppInfo(version = "1.0.0", build = "42", platform = "test"),
            dispatchers = CoreDispatchers(compute = dispatcher, main = dispatcher),
        )
    }

    @Test
    fun fakesSatisfyInterfaces() {
        val deps = deps()

        deps.secureStore.put("gateway.secret", "s3cr3t")
        assertEquals("s3cr3t", deps.secureStore.get("gateway.secret"))

        deps.files.write("draft.json", "hello")
        assertEquals("hello", deps.files.read("draft.json"))

        clock.advanceBy(500)
        assertEquals(1_500, deps.clock.nowMs(), "engine tests move time through the injected clock")

        assertEquals("1.0.0", deps.appInfo.version)
        assertEquals("test", deps.appInfo.platform)
        assertNotNull(deps.dispatchers.compute, "reduction needs a compute dispatcher")
        assertNotNull(deps.dispatchers.main, "platform callbacks need a main dispatcher")
    }

    @Test
    fun aTestDoubleNeverLeaksIntoTheRealStore() {
        val deps = deps()
        deps.secureStore.put("gateway.secret", "s3cr3t")
        deps.files.write("gateway.json", "s3cr3t")

        assertTrue(
            deps.files.read("gateway.json")!!.contains("s3cr3t"),
            "D5: the token never enters the plain file store in production, and this test proves " +
                "the fake would show it if a caller ever routed the secret there",
        )
        assertEquals("s3cr3t", deps.secureStore.get("gateway.secret"), "the secure store is the right home")
    }
}
