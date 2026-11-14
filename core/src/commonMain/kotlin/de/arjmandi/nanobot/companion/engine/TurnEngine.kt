package de.arjmandi.nanobot.companion.engine

import de.arjmandi.nanobot.companion.projection.Effect
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.ConnectionResult
import de.arjmandi.nanobot.companion.transport.CoreError
import de.arjmandi.nanobot.companion.transport.Socket
import de.arjmandi.nanobot.companion.transport.classifyFailure
import de.arjmandi.nanobot.companion.wire.BootstrapPayload
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.Outbound
import de.arjmandi.nanobot.companion.wire.OutboundCodec
import de.arjmandi.nanobot.companion.wire.ReadyFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the engine needs from whoever owns the state: reduce one frame, be told the socket
 * opened or went away, and take back the effects the engine has no business executing.
 */
internal abstract class EngineHost {

    /** Bootstrap answered, before the first frame: limits and capabilities are known here. */
    internal abstract suspend fun opening(payload: BootstrapPayload)

    /** One frame in, the work it produced out. */
    internal abstract suspend fun frame(frame: Frame): List<Effect>

    /** The socket is gone; null when the client asked for that. */
    internal abstract suspend fun closed(error: CoreError?)

    /** An effect only the host can carry out: persistence, delivery, the prompt queue. */
    internal abstract suspend fun effect(effect: Effect)
}

internal sealed interface EngineOpen {
    data class Ready(val payload: BootstrapPayload) : EngineOpen
    data class Failed(val error: CoreError) : EngineOpen
}

/**
 * Owns the socket (plan.md §2). One pump reads frames and hands them to the host, one writer
 * drains a single channel onto the socket, and every send goes through that channel — so
 * frame order is the order the caller asked for, and there is exactly one thing writing.
 *
 * Reduction happens in the host: the engine never decides what a frame means, which is why a
 * connect/send/close scenario runs on a fake transport with no reducer at all.
 */
internal class TurnEngine(
    private val scope: CoroutineScope,
    private val attempt: ConnectionAttempt,
    private val host: EngineHost,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
) {

    private var connection: Job? = null
    private var outbound: Channel<Outbound>? = null
    private var closing = false

    val isOpen: Boolean
        get() = outbound != null

    /** Jobs this engine started and has not joined; a leak check reads this. */
    fun activeChildJobs(): Int = connection?.children?.count { it.isActive } ?: 0

    /**
     * Bootstrap, handshake, and wait for `ready`. A reconnect is a new socket with a token
     * minted for it (C3.4): the previous one is torn down first, never resumed.
     */
    suspend fun connect(secret: String): EngineOpen {
        close()
        return when (val opened = attempt.open(secret)) {
            is ConnectionResult.Failed -> EngineOpen.Failed(opened.error)
            is ConnectionResult.Open -> start(opened.payload, opened.socket)
        }
    }

    /** Put one envelope on the wire. False means the socket is not there to carry it. */
    suspend fun send(frame: Outbound): Boolean {
        val channel = outbound ?: return false
        return try {
            channel.send(frame)
            true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            false
        }
    }

    /**
     * Cancel the pump and the writer, then join them. Closing the channel first lets the
     * writer finish what is already queued; cancelling after means a socket that will not
     * close cannot hold the teardown open.
     */
    suspend fun close() {
        val channel = outbound
        val group = connection
        outbound = null
        connection = null
        closing = true
        try {
            channel?.close()
            group?.cancelAndJoin()
        } finally {
            closing = false
        }
    }

    private suspend fun start(payload: BootstrapPayload, socket: Socket): EngineOpen {
        host.opening(payload)

        val channel = Channel<Outbound>(Channel.UNLIMITED)
        // One job per connection, so a teardown is one cancel and a leak check is one count.
        val job = Job(scope.coroutineContext[Job])
        val group = CoroutineScope(scope.coroutineContext + job)
        connection = job
        outbound = channel
        val ready = CompletableDeferred<Unit>()

        group.launch {
            try {
                for (frame in channel) socket.send(OutboundCodec.encode(frame).toString())
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                // A write that fails means the socket is gone; the pump sees the close and
                // reports it, so there is nothing to decide here.
                channel.close()
            }
        }

        group.launch {
            var error: CoreError? = null
            try {
                socket.frames.collect { frame ->
                    host.frame(frame).forEach { effect -> execute(effect, channel) }
                    // `ready` means the host has already processed the greeting: a caller that
                    // sends the moment connect returns must find the chat the greeting minted.
                    if (frame is ReadyFrame && !ready.isCompleted) ready.complete(Unit)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = classifyFailure(failure)
            } finally {
                // A pump that ends is an engine with no socket: the writer must stop with
                // it, sends must be refused, and the host must hear about a socket that
                // left on its own — but not one the client closed on purpose.
                outbound = null
                channel.close()
                if (!closing) host.closed(error)
            }
        }

        val greeted = withTimeoutOrNull(readyTimeoutMs) { ready.await() }
        if (greeted == null) {
            close()
            return EngineOpen.Failed(CoreError.Timeout)
        }
        return EngineOpen.Ready(payload)
    }

    /** One frame to send is the engine's work; everything else belongs to the host. */
    private suspend fun execute(effect: Effect, channel: Channel<Outbound>) {
        when (effect) {
            is Effect.Send -> channel.send(effect.frame)
            else -> host.effect(effect)
        }
    }

    private companion object {
        /** The gateway greets a socket immediately; a silent one is a stuck host, not a slow one. */
        const val READY_TIMEOUT_MS: Long = 20_000
    }
}
