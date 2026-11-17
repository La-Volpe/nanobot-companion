package de.arjmandi.nanobot.companion.bridge

import de.arjmandi.nanobot.companion.projection.TaskNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A `StateFlow` as Swift can actually use it. Kotlin/Native exports `StateFlow` as an untyped
 * protocol whose `value` is `id`, so a Swift screen reading `core.connection.value` gets `Any?`
 * and has to cast, in every view, for every state.
 * This is that cast, once, and the subscription SwiftUI needs on top of it.
 *
 * The generic parameter is what makes it useful: `ObservableState<ConnectionState>` in
 * Swift names the state type it carries, so `subscribe`'s callback is typed and nothing
 * downstream of the adapter layer casts.
 */
public class ObservableState<T : Any> internal constructor(
    private val flow: StateFlow<T>,
    private val scope: CoroutineScope,
) {

    /** UI state arrives on the main queue by default: a SwiftUI body is never off-main. */
    public constructor(flow: StateFlow<T>) : this(flow, CoroutineScope(Dispatchers.Main + SupervisorJob()))

    public val value: T get() = flow.value

    /**
     * The current value, then every change until [Cancellable.cancel]. The first value is
     * delivered before this call returns so a view model has state on its first body rather
     * than one frame later.
     *
     * Values are deduplicated against the last one delivered, because the synchronous read and
     * the collector's first emission are usually the same value, and because a value that
     * changes between them must still be delivered, which a plain "skip the first emission"
     * would lose.
     */
    public fun subscribe(onChange: (T) -> Unit): Cancellable {
        var last: T? = null
        fun deliver(next: T) {
            if (next == last) return
            last = next
            onChange(next)
        }
        deliver(flow.value)
        val job = scope.launch { flow.collect { deliver(it) } }
        return Cancellable(job)
    }
}

/** What `subscribe` returns. A Swift view stores it and cancels on disappear. */
public class Cancellable internal constructor(private val job: Job) {

    public val isCancelled: Boolean get() = job.isCancelled

    public fun cancel() {
        job.cancel()
    }
}

/**
 * The core's notices, as a closure (`ObservableState` is generic over a non-null value, and
 * "nothing to announce yet" is a real state here). Delivered on the main queue, deduplicated
 * against the last value: the platform posts what arrives and never re-posts a repeat.
 */
public fun observeTaskNotices(flow: kotlinx.coroutines.flow.StateFlow<TaskNotice?>, onChange: (TaskNotice?) -> Unit): Cancellable {
    var last: TaskNotice? = null
    var first = true
    fun deliver(next: TaskNotice?) {
        if (!first && next == last) return
        first = false
        last = next
        onChange(next)
    }
    deliver(flow.value)
    val job = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
        flow.collect { deliver(it) }
    }
    return Cancellable(job)
}
