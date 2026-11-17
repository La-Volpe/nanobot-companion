package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.BootstrapPayload

/** Bootstrap as a function, so a reconnect test can mint a fresh token per attempt. */
internal fun interface BootstrapSource {
    suspend fun fetch(secret: String): BootstrapOutcome
}

internal sealed interface ConnectionResult {
    data class Open(val payload: BootstrapPayload, val socket: Socket) : ConnectionResult
    data class Failed(val error: CoreError) : ConnectionResult
}

/**
 * One connection attempt: bootstrap, then handshake with the token that bootstrap just
 * minted. Attempts are single-shot and never reused, because a token that has been through
 * a handshake is dead, so a "reconnect" that skips the bootstrap can only fail silently.
 */
internal class ConnectionAttempt(
    private val bootstrap: BootstrapSource,
    private val openSocket: (String) -> Socket,
    private val clientId: String = "nanobot-companion",
) {

    suspend fun open(secret: String): ConnectionResult =
        when (val outcome = bootstrap.fetch(secret)) {
            is BootstrapOutcome.Failed -> ConnectionResult.Failed(outcome.error)
            is BootstrapOutcome.Ready -> {
                // A host that speaks a protocol this client does not know is stopped here,
                // before a socket exists that would only produce frames we misread.
                val contractProblem = outcome.payload.contract.problems(de.arjmandi.nanobot.companion.wire.Feature.Core)
                if (contractProblem != null) {
                    ConnectionResult.Failed(coreErrorOf(contractProblem))
                } else {
                    ConnectionResult.Open(
                        payload = outcome.payload,
                        socket = openSocket(outcome.payload.socketUrl(clientId)),
                    )
                }
            }
        }
}

/** `ws://host:port/?client_id=…&token=…`, the handshake URL shape the greeting answers. */
internal fun BootstrapPayload.socketUrl(clientId: String): String {
    val separator = if (wsUrl.contains('?')) '&' else '?'
    return "$wsUrl${separator}client_id=$clientId&token=$token"
}
