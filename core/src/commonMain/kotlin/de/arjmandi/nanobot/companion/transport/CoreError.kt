package de.arjmandi.nanobot.companion.transport

/**
 * The eight failures the UI has to be able to explain (plan.md §6, C7.4). They are
 * deliberately few and named for the user's problem, not the library's: a socket that
 * refuses is "unreachable" whether Ktor calls it IOException or ConnectException.
 */
public sealed interface CoreError {

    /** The gateway does not accept this secret: 401 or 403. */
    public data object AuthFailed : CoreError

    /** The certificate is not trusted (self-signed home gateway, or a real mismatch). */
    public data object TlsRejected : CoreError

    /** Nothing is listening: DNS, refused connection, no route. */
    public data object Unreachable : CoreError

    /** Connect or read took too long. */
    public data object Timeout : CoreError

    /** The host speaks a protocol newer than this client. */
    public data class ProtocolTooNew(public val hostMinProtocol: Int, public val clientProtocol: Int) : CoreError

    /** The host is fine but lacks one capability, so one feature is hidden. */
    public data class MissingCapability(public val feature: String, public val capability: String) : CoreError

    /** A required field was missing from a payload the client needs. */
    public data class MissingField(public val field: String) : CoreError

    /** The gateway answered, but not successfully. */
    public data class ServerError(public val status: Int, public val detail: String? = null) : CoreError
}
