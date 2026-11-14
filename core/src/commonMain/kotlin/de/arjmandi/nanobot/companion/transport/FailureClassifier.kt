package de.arjmandi.nanobot.companion.transport

/**
 * Ktor engines report the same user-facing problem as different exception types on each
 * platform (JVM `SSLHandshakeException`, Darwin `NSURLErrorServerCertificateUntrusted`
 * surfaced as its localized message). One classifier keeps the eight-entry taxonomy
 * from becoming three taxonomies.
 */
internal fun classifyFailure(failure: Throwable): CoreError {
    if (failure is CoreException) return failure.error

    val name = failure::class.simpleName.orEmpty()
    val message = failure.message.orEmpty()
    val probe = "$name $message"

    return when {
        name.contains("Timeout", ignoreCase = true) || probe.contains("timed out", ignoreCase = true) ->
            CoreError.Timeout

        probe.contains("PKIX", ignoreCase = true) ||
            probe.contains("certificate", ignoreCase = true) ||
            probe.contains("serverTrust", ignoreCase = true) ||
            probe.contains("SSLHandshake", ignoreCase = true) ||
            probe.contains("not trusted", ignoreCase = true) ->
            CoreError.TlsRejected

        probe.contains("ConnectException", ignoreCase = true) ||
            probe.contains("UnknownHost", ignoreCase = true) ||
            probe.contains("UnresolvedAddress", ignoreCase = true) ||
            probe.contains("Connection refused", ignoreCase = true) ||
            probe.contains("No route to host", ignoreCase = true) ||
            probe.contains("offline", ignoreCase = true) ->
            CoreError.Unreachable

        // Anything else from the socket layer is still "we could not reach it", never a crash.
        else -> CoreError.Unreachable
    }
}

/** Carries a taxonomy entry through the places that can only throw. */
internal class CoreException(val error: CoreError) : Exception(error.toString())
