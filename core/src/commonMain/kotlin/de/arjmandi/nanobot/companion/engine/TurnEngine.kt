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
 * One frame and the writer's verdict on it. A caller that asks "was it sent?" cannot be answered
 * by the queue: the queue takes everything, and the socket is what refuses. A receipt that said
 * `Accepted` about a frame the socket never took is what this shape prevents.
 */
private class Queued(val frame: Outbound) {
    val written: CompletableDeferred<Boolean> = CompletableDeferred()
}

/**
 * Owns the socket. One pump reads frames and hands them to the host, one writer drains a single
 * channel onto the socket, and every send goes through that channel, so frame order is the order
 * the caller asked for and there is exactly one thing writing.
 *
 * Reduction happens in the host: the engine never decides what a frame means, which is why a
 * connect/send/close scenario runs on a fake transport with no reducer at all.
 */
internal class TurnEngine(
    private val scope: CoroutineScope,
    private val attempt: ConnectionAttempt,
    private val host: EngineHost,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
    private val heartbeat: HeartbeatPolicy = HeartbeatPolicy(),
) {

    private var connection: Job? = null
    private var outbound: Channel<Queued>? = null
    private var closing = false

    /** Wakes the heartbeat: the timer and a return to the foreground ask the same question. */
    private var askNow: Channel<Unit>? = null

    /** Whether the app is in front of the user; a pocketed phone is not asked anything. */
    private var watched = false

    /** The chat the greeting minted; the heartbeat asks the gateway about it. */
    private var heartbeatChatId: String? = null

    val isOpen: Boolean
        get() = outbound != null

    /** Jobs this engine started and has not joined; a leak check reads this. */
    fun activeChildJobs(): Int = connection?.children?.count { it.isActive } ?: 0

    /**
     * Bootstrap, handshake, and wait for `ready`. A reconnect is a new socket with a token
     * minted for it: the previous one is torn down first, never resumed.
     */
    suspend fun connect(secret: String): EngineOpen {
        close()
        return when (val opened = attempt.open(secret)) {
            is ConnectionResult.Failed -> EngineOpen.Failed(opened.error)
            is ConnectionResult.Open -> start(opened.payload, opened.socket)
        }
    }

    /**
     * Whether anyone is watching. Coming back from away asks the socket at once (the process was
     * suspended, no timer ran, and a network change in between leaves a connection nobody read
     * half-open with no FIN to notice), and while the user is here it is asked every interval.
     * Nothing is asked in the background: a phone in a pocket has no timer to keep, and the
     * return is the moment the answer matters.
     */
    fun setWatched(watched: Boolean) {
        this.watched = watched
        if (watched) askNow?.trySend(Unit)
    }

    /**
     * Put one envelope on the wire, and answer what the socket did with it. False means the
     * socket is not there to carry it, or did not take it, which is the same answer to a caller
     * deciding whether a message landed.
     */
    suspend fun send(frame: Outbound): Boolean {
        val channel = outbound ?: return false
        val queued = Queued(frame)
        return try {
            channel.send(queued)
            // The write, not the queue. A socket that never gets to the frame leaves the
            // caller waiting only for this window, and "not sent" is the honest answer.
            withTimeoutOrNull(WRITE_ACK_MS) { queued.written.await() } ?: false
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
        askNow = null
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

        val channel = Channel<Queued>(Channel.UNLIMITED)
        // One unit per frame in, drained by the heartbeat: the only proof of life a socket can
        // give is a frame coming back, and every frame counts, not just the answer to a question.
        val answers = Channel<Unit>(Channel.CONFLATED)
        val asked = Channel<Unit>(Channel.CONFLATED)
        // One job per connection, so a teardown is one cancel and a leak check is one count.
        val job = Job(scope.coroutineContext[Job])
        val group = CoroutineScope(scope.coroutineContext + job)
        connection = job
        outbound = channel
        askNow = asked
        // A new socket is not subscribed to the previous one's chats.
        heartbeatChatId = null
        val ready = CompletableDeferred<Unit>()

        group.launch {
            try {
                for (queued in channel) {
                    val written = try {
                        socket.send(OutboundCodec.encode(queued.frame).toString())
                        true
                    } catch (failure: CancellationException) {
                        queued.written.complete(false)
                        throw failure
                    } catch (failure: Exception) {
                        false
                    }
                    queued.written.complete(written)
                    if (!written) break
                }
            } finally {
                // A write that fails means the socket is gone; the pump sees the close and
                // reports it, so there is nothing to decide here. What is left in the channel
                // will never be written, so it is settled rather than left waiting.
                channel.close()
                while (true) {
                    val left = channel.tryReceive().getOrNull() ?: break
                    left.written.complete(false)
                }
            }
        }

        // The heartbeat is a second reader of the same connection, not a second socket: it asks
        // about the chat the greeting minted, because the gateway answers an `attach` with an
        // `attached` (inbound_commands.py `attach`) and answers nothing else for free.
        val heartbeatJob = group.launch {
            while (true) {
                // Parked with no timer at all while the app is away, so a sleeping phone keeps
                // nothing alive; a return wakes this with one trigger.
                val askedNow = if (watched) {
                    withTimeoutOrNull(heartbeat.intervalMs) { asked.receive() } != null
                } else {
                    asked.receive()
                    true
                }
                // A timer that ran out after the app left is not a reason to ask anything.
                if (!askedNow && !watched) continue
                val chatId = heartbeatChatId ?: continue
                if (closing) return@launch
                if (!ask(chatId, channel, answers)) {
                    giveUp(channel, answers)
                    return@launch
                }
            }
        }

        group.launch {
            var error: CoreError? = null
            try {
                socket.frames.collect { frame ->
                    answers.trySend(Unit)
                    host.frame(frame).forEach { effect -> execute(effect, channel) }
                    if (frame is ReadyFrame) {
                        // The greeting names the chat the heartbeat asks about: a socket that
                        // dies without a close is only visible as a question with no answer.
                        frame.chatId?.let { heartbeatChatId = it }
                        // `ready` means the host has already processed the greeting: a caller
                        // that sends the moment connect returns must find the chat it minted.
                        if (!ready.isCompleted) ready.complete(Unit)
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = classifyFailure(failure)
            } finally {
                // A pump that ends is an engine with no socket: the writer must stop with
                // it, sends must be refused, and the host must hear about a socket that
                // left on its own, but not one the client closed on purpose.
                outbound = null
                askNow = null
                channel.close()
                heartbeatJob.cancel()
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

    /**
     * One question on the wire and the time the gateway gets to answer it. A frame that arrived
     * before the question was asked is not an answer to it, so anything already buffered is
     * dropped first, otherwise a busy socket would look alive for an interval after it died.
     */
    private suspend fun ask(chatId: String, channel: Channel<Queued>, answers: Channel<Unit>): Boolean {
        drain(answers)
        return try {
            channel.send(Queued(Outbound.Attach(chatId = chatId)))
            withTimeoutOrNull(heartbeat.windowMs) { answers.receive() } != null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // A question the socket could not carry is the same answer as one it ignored.
            false
        }
    }

    private fun drain(answers: Channel<Unit>) {
        while (true) {
            if (answers.tryReceive().isFailure) return
        }
    }

    /**
     * The socket stopped answering. Report it the way the pump would have (one banner, the
     * existing "Try again", no second failure surface) and take the same teardown a close takes.
     * `closing` is what stops the pump from reporting the same death twice.
     */
    private suspend fun giveUp(channel: Channel<Queued>, answers: Channel<Unit>) {
        closing = true
        outbound = null
        askNow = null
        channel.close()
        answers.close()
        host.closed(CoreError.Timeout)
        // From inside the connection's own job: cancel, never join, or the teardown waits on
        // the coroutine asking for it.
        connection?.cancel()
    }

    /** One frame to send is the engine's work; everything else belongs to the host. */
    private suspend fun execute(effect: Effect, channel: Channel<Queued>) {
        when (effect) {
            is Effect.Send -> channel.send(Queued(effect.frame))
            else -> host.effect(effect)
        }
    }

    private companion object {
        /** The gateway greets a socket immediately; a silent one is a stuck host, not a slow one. */
        const val READY_TIMEOUT_MS: Long = 20_000

        /**
         * How long a caller waits for the socket to take one frame. A local write lands in
         * milliseconds; five seconds is a socket that is not there, and a composer left with
         * its text is better than one that waits on a connection nobody answered.
         */
        const val WRITE_ACK_MS: Long = 5_000
    }
}
