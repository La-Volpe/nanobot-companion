package de.arjmandi.nanobot.companion.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The subscription wrapper Swift uses in place of collecting a `StateFlow` (C8.2). The
 * generated header hands Swift `id<...StateFlow>` whose `value` is `Any?`, so a screen
 * cannot read its own state without a cast; this class is the one place that cast lives.
 *
 * The three properties that make it usable from SwiftUI are asserted here: the current
 * value arrives before the call returns (so a view has state on its first body), later
 * values arrive once each, and a cancelled subscription stops delivering.
 */
class ObservableStateTest {

    @Test
    fun deliversTheCurrentValueBeforeTheCallReturns() = runTest {
        val flow = MutableStateFlow("idle")
        val seen = mutableListOf<String>()
        val observer = ObservableState(flow, backgroundScope)

        observer.subscribe { seen += it }

        assertEquals(listOf("idle"), seen, "a subscriber starts from the current value, not the next one")
    }

    @Test
    fun deliversEverySubsequentValueOnce() = runTest {
        val flow = MutableStateFlow("idle")
        val seen = mutableListOf<String>()
        val observer = ObservableState(flow, backgroundScope)
        observer.subscribe { seen += it }

        flow.value = "connecting"
        runCurrent()
        flow.value = "open"
        runCurrent()

        assertEquals(listOf("idle", "connecting", "open"), seen)
    }

    @Test
    fun doesNotRepeatTheCurrentValueWhenCollectionStarts() = runTest {
        // The first synchronous read and the collector's first emission are the same value;
        // delivering it twice would make a view model rebuild itself on every subscribe.
        val flow = MutableStateFlow(1)
        val seen = mutableListOf<Int>()
        val observer = ObservableState(flow, backgroundScope)

        observer.subscribe { seen += it }
        runCurrent()

        assertEquals(listOf(1), seen)
    }

    @Test
    fun aValueThatChangesBeforeCollectionStartsIsStillDelivered() = runTest {
        val flow = MutableStateFlow(1)
        val seen = mutableListOf<Int>()
        val observer = ObservableState(flow, backgroundScope)
        // Between the synchronous read and the coroutine starting, the state moves on.
        flow.value = 2
        observer.subscribe { seen += it }

        assertEquals(listOf(2), seen, "the subscriber must not be left on a stale value")
    }

    @Test
    fun cancelStopsDelivery() = runTest {
        val flow = MutableStateFlow(1)
        val seen = mutableListOf<Int>()
        val observer = ObservableState(flow, backgroundScope)
        val subscription = observer.subscribe { seen += it }

        subscription.cancel()
        flow.value = 2
        runCurrent()

        assertEquals(listOf(1), seen)
        assertTrue(subscription.isCancelled, "cancel is observable, so a view can prove it let go")
    }

    @Test
    fun cancelTwiceIsHarmless() = runTest {
        // A SwiftUI view that disappears and a view model that is torn down both cancel.
        val observer = ObservableState(MutableStateFlow(1), backgroundScope)
        val subscription = observer.subscribe { }

        subscription.cancel()
        subscription.cancel()

        assertTrue(subscription.isCancelled)
    }

    @Test
    fun valueReadsTheLatestWithoutSubscribing() = runTest {
        val flow = MutableStateFlow("a")
        val observer = ObservableState(flow, backgroundScope)

        flow.value = "b"

        assertEquals("b", observer.value)
    }

    @Test
    fun twoSubscribersEachGetTheirOwnValues() = runTest {
        val flow = MutableStateFlow(1)
        val first = mutableListOf<Int>()
        val second = mutableListOf<Int>()
        val observer = ObservableState(flow, backgroundScope)

        val one = observer.subscribe { first += it }
        val two = observer.subscribe { second += it }
        flow.value = 2
        runCurrent()
        one.cancel()
        flow.value = 3
        runCurrent()

        assertEquals(listOf(1, 2), first)
        assertEquals(listOf(1, 2, 3), second)
        two.cancel()
    }
}
