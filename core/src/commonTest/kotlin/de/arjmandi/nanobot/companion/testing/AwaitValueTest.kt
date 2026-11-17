package de.arjmandi.nanobot.companion.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * The wait these tests use instead of the clock. The point of the test below is the *thread*:
 * the value arrives from a real one after a real delay, which is what the mock engine's answer
 * does, and the virtual clock is exactly what `advanceUntilIdle()` would have moved instead.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AwaitValueTest {

    @Test
    fun aValueThatArrivesOnARealThreadIsAwaited() = runTest {
        val states = MutableStateFlow(0)
        val writer = launch(Dispatchers.Default) {
            delay(200)
            states.value = 1
        }

        assertEquals(1, states.awaitValue(what = "the answer on a real thread") { it == 1 })
        writer.join()
    }

    @Test
    fun theTimeoutNamesWhatNeverArrived() = runTest {
        val states = MutableStateFlow("loading")

        val error = assertFailsWith<AssertionError> {
            states.awaitValue(timeoutMs = 50, what = "the replayed page") { it == "loaded" }
        }

        assertTrue(
            error.message.orEmpty().contains("the replayed page"),
            "the failure has to say which state never arrived, not just that a wait expired: ${error.message}",
        )
    }
}
