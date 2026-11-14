package de.arjmandi.nanobot.companion.transport

/**
 * The eight failures the UI has to be able to explain (plan.md §6, C7.4). They are
 * deliberately few and named for the user's problem, not the library's: a socket that
 * refuses is "unreachable" whether Ktor calls it IOException or ConnectException.
 */
internal sealed interface CoreError {

    /** The gateway does not accept this secret: 401 or 403. */
    data object AuthFailed : CoreError

    /** The certificate is not trusted (self-signed home gateway, or a real mismatch). */
    data object TlsRejected : CoreError

    /** Nothing is listening: DNS, refused connection, no route. */
    data object Unreachable : CoreError

    /** Connect or read took too long. */
    data object Timeout : CoreError

    /** The host speaks a protocol newer than this client. */
    data class ProtocolTooNew(val hostMinProtocol: Int, val clientProtocol: Int) : CoreError

    /** The host is fine but lacks one capability, so one feature is hidden. */
    data class MissingCapability(val feature: String, val capability: String) : CoreError

    /** A required field was missing from a payload the client needs. */
    data class MissingField(val field: String) : CoreError

    /** The gateway answered, but not successfully. */
    data class ServerError(val status: Int, val detail: String? = null) : CoreError
}
