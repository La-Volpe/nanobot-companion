package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * A socket in two lists: the envelopes the engine wrote, and the frames a script pushes at
 * it. `opened` and `closed` count what a leak check needs: a socket that was opened and never
 * closed is exactly the failure a reconnect is looking for.
 */
internal class FakeSocket : Socket {

    private val incoming = Channel<Frame>(Channel.UNLIMITED)

    val sent: MutableList<String> = mutableListOf()

    /**
     * What the gateway does with a write. A test that needs an answer (the heartbeat needs one)
     * sets this instead of racing the engine with a `push` from outside.
     */
    var answer: (suspend (String) -> Unit)? = null

    /**
     * A socket whose writes fail: Ktor's `send` throws when the connection is gone, and a client
     * that reports a message as taken anyway is the receipt lying.
     */
    var refusesWrites: Boolean = false

    var opened: Int = 0
        private set

    var closed: Int = 0
        private set

    override val frames: Flow<Frame> = flow {
        opened += 1
        try {
            for (frame in incoming) emit(frame)
        } finally {
            closed += 1
        }
    }

    override suspend fun send(text: String) {
        if (refusesWrites) throw IllegalStateException("the socket refused the write")
        sent += text
        answer?.invoke(text)
    }

    /** A frame from the gateway, decoded exactly as the real pump decodes one. */
    fun push(json: String) {
        incoming.trySend(FrameCodec.decode(json))
    }

    fun end() {
        incoming.close()
    }
}

/**
 * A socket that says its piece and closes: for the handshake tests, where the question is
 * which token went out and not what happens next.
 */
internal class FiniteSocket(vararg frames: Frame) : Socket {

    val sent: MutableList<String> = mutableListOf()

    override val frames: Flow<Frame> = flowOf(*frames)

    override suspend fun send(text: String) {
        sent += text
    }
}
