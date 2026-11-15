package de.arjmandi.nanobot.companion

import de.arjmandi.nanobot.companion.states.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The two ways the phone asks for a socket it has lost (A8): the banner's "Try again", and coming
 * back to the foreground after Android backgrounded the app. Both come through here because both
 * have the same two rules — one attempt at a time, and nothing to recover on a socket that was
 * never up (the Android twin of iOS's `ConnectionRecovery`).
 *
 * What makes the retry worth offering: the core's `connect()` bootstraps a fresh single-use token
 * on every call, so a retry is a real handshake, never a replay of a dead one (C3.4).
 */
class ConnectionRecovery(
    /** The core's connect on the stack that is already open; true when the gateway greeted it. */
    private val reconnect: suspend () -> Boolean,
    /** The screen's own scope: a tap is not a suspend function and must not block the frame. */
    private val scope: CoroutineScope,
) {

    /**
     * The reads that need the `api_token` the new handshake minted, plus the chat the new socket
     * has not been told about. Set once by the composition root, which is what knows the chat.
     */
    var reload: suspend () -> Unit = {}

    var isRunning: Boolean = false
        private set

    /**
     * "Try again": the button exists only under a banner that says the socket is lost, and the
     * user asked, so the state must not talk them out of it. The one thing it will never do is
     * race a connect already in flight — two bootstraps is two sockets and a token burned twice.
     */
    fun retry(status: ConnectionStatus) {
        if (status == ConnectionStatus.Connecting) return
        start()
    }

    /**
     * Back in the foreground. Nothing to do for a phone that never connected (it is on onboarding,
     * not on a broken socket), for a socket that is still up, or for a connect whose answer is
     * already on its way.
     */
    fun resumed(status: ConnectionStatus, everConnected: Boolean) {
        if (!everConnected) return
        if (status == ConnectionStatus.Open || status == ConnectionStatus.Connecting) return
        start()
    }

    private fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                if (reconnect()) reload()
            } finally {
                isRunning = false
            }
        }
    }
}
