package de.arjmandi.nanobot.companion.platform

/**
 * The trust boundary. A self-signed home gateway is the common case, so the client can be told
 * to accept an unverified certificate, but only for the gateway the user turned it on for, and
 * never at the cost of the hostname check.
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

        /**
         * The policy for one gateway address, built where onboarding collects it. The host is
         * what [TlsTrustRule] matches a challenge against, so it is read out of the address the
         * user typed rather than kept as a separate field nobody fills in.
         */
        fun forGateway(baseUrl: String, trustSelfSignedCertificates: Boolean): TlsPolicy =
            TlsPolicy(trustSelfSignedCertificates = trustSelfSignedCertificates, host = hostOf(baseUrl))

        /**
         * The host in an address a person types: with or without a scheme, with a port, with a
         * trailing slash, and with credentials in front of it as a pasted URL can be. The port
         * and path are not part of what a certificate matches.
         */
        fun hostOf(baseUrl: String): String? {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) return null
            val withoutScheme = trimmed.substringAfter("://", trimmed)
            val authority = withoutScheme.substringBefore('/').substringBefore('?')
            val withoutCredentials = authority.substringAfterLast('@')
            // A bracketed IPv6 literal keeps its brackets: that is how it appears in an address.
            if (withoutCredentials.startsWith("[")) return withoutCredentials.substringBefore(']').plus("]").takeIf { it.length > 2 }
            val host = withoutCredentials.substringBefore(':')
            return host.takeIf { it.isNotEmpty() }
        }
    }
}
