package de.arjmandi.nanobot.companion.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The state, not the clock. A mock engine answers on its own thread, so `runCurrent()` and
 * `advanceUntilIdle()` say nothing about whether a read has landed: they move the virtual clock,
 * which the answer never joined. This waits on the state the read fills instead, and names what
 * never arrived when it does not.
 *
 * The wait runs on a real dispatcher for the same reason: `withTimeoutOrNull` on the test
 * scheduler's clock measures virtual time, and `runTest`'s runner skips to the timeout while the
 * real thread is still working (`AwaitValueTest.aValueThatArrivesOnARealThreadIsAwaited`).
 */
suspend fun <T> Flow<T>.awaitValue(
    timeoutMs: Long = 3_000,
    what: String,
    predicate: (T) -> Boolean,
): T = withContext(Dispatchers.Default) {
    withTimeoutOrNull(timeoutMs) { first(predicate) }
} ?: throw AssertionError("$what did not arrive within ${timeoutMs}ms")
