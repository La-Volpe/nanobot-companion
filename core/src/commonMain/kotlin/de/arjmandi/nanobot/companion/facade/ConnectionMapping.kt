package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.transport.CoreError

/**
 * The taxonomy as a screen state. One function, exhaustive over [CoreError], so
 * a new failure cannot reach the UI as an unhandled string: the compiler makes the author pick
 * a state and a copy key before it builds.
 */
internal fun CoreError.connectionFailure(): ConnectionState.Failed = when (this) {
    CoreError.AuthFailed -> ConnectionState.Failed(ConnectionFailure.AuthFailed)
    CoreError.TlsRejected -> ConnectionState.Failed(ConnectionFailure.TlsRejected)
    CoreError.Unreachable -> ConnectionState.Failed(ConnectionFailure.Unreachable)
    CoreError.Timeout -> ConnectionState.Failed(ConnectionFailure.Timeout)

    is CoreError.ProtocolTooNew -> ConnectionState.Failed(
        failure = ConnectionFailure.ProtocolTooNew,
        detail = "$hostMinProtocol/$clientProtocol",
    )

    is CoreError.MissingCapability -> ConnectionState.Failed(
        failure = ConnectionFailure.MissingCapability,
        detail = capability,
    )

    is CoreError.MissingField -> ConnectionState.Failed(
        failure = ConnectionFailure.MissingField,
        detail = field,
    )

    is CoreError.ServerError -> ConnectionState.Failed(
        failure = ConnectionFailure.ServerError,
        // A 5xx may be a bad minute; a 4xx is the same answer every time.
        retryable = status >= 500,
        detail = status.toString(),
    )
}
