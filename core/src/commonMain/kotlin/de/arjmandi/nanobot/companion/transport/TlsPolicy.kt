package de.arjmandi.nanobot.companion.transport

/**
 * The trust boundary (plan.md C3.2). A self-signed home gateway is the common case, so the
 * client can be told to accept an unverified certificate — but only for the gateway the
 * user turned it on for, and never at the cost of the hostname check.
 */
internal data class TlsPolicy(
    val trustSelfSignedCertificates: Boolean = false,
    val host: String? = null,
    val verifiesHostname: Boolean = true,
) {
    /** Onboarding must show the warning before this can be persisted as true. */
    val requiresConfirmation: Boolean get() = trustSelfSignedCertificates

    internal companion object {
        val DEFAULT: TlsPolicy = TlsPolicy()
    }
}
