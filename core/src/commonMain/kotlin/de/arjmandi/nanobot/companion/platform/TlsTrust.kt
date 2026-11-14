package de.arjmandi.nanobot.companion.platform

/**
 * What to do with one TLS challenge (plan.md C3.2, D21). Two answers are enough for both
 * platforms: hand it to the system's own validation, or accept the server's certificate
 * because the user said this gateway's certificate is theirs.
 */
internal enum class TlsTrust {
    /** The platform decides. Everything that is not an accepted self-signed certificate. */
    Default,

    /** Accept the certificate the challenge offers, for this host and this connection only. */
    AcceptServerTrust,
}

/**
 * The rule behind the onboarding switch, kept pure so it is the same rule on both platforms
 * and every cell of it has a test. The platform layers only translate the answer into
 * `NSURLSessionAuthChallengeDisposition` or an OkHttp `X509TrustManager`.
 *
 * The two refusals that make the switch safe:
 *
 *  - it applies to a **server-trust** challenge only, so a basic-auth or client-certificate
 *    prompt still goes to the system with the switch on;
 *  - it applies to the **configured host** only, so a self-signed certificate accepted for the
 *    user's own gateway cannot be accepted for a host they never typed.
 */
internal object TlsTrustRule {

    fun decide(policy: TlsPolicy, isServerTrust: Boolean, challengeHost: String?): TlsTrust {
        if (!isServerTrust) return TlsTrust.Default
        if (!policy.trustSelfSignedCertificates) return TlsTrust.Default
        val gateway = policy.host ?: return TlsTrust.Default
        return if (challengeHost == gateway) TlsTrust.AcceptServerTrust else TlsTrust.Default
    }
}
