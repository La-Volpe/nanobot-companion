package de.arjmandi.nanobot.companion.platform

import kotlinx.cinterop.ExperimentalForeignApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.ChallengeHandler
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust

/**
 * The iOS half of the trust decision: [TlsTrustRule] decides, this translates. Nothing here
 * evaluates a certificate chain, because the system still validates every certificate; the
 * switch only changes what happens when that validation rejects the *anchor*, which is exactly
 * what a self-signed home gateway looks like.
 *
 * `protectionSpace.serverTrust` is the trust the system already built for this connection, so
 * the credential accepts that certificate for that host. It is never a global "trust anything":
 * the rule has already refused every host the user did not type.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun iosChallengeHandler(policy: TlsPolicy): ChallengeHandler = { _, _, challenge, complete ->
    when (decideFor(policy, challenge)) {
        TlsTrust.AcceptServerTrust -> {
            val credential = challenge.protectionSpace.serverTrust?.let { NSURLCredential.credentialForTrust(it) }
            if (credential != null) {
                complete(NSURLSessionAuthChallengeUseCredential, credential)
            } else {
                // No trust object to accept means there is nothing the switch could trust.
                complete(NSURLSessionAuthChallengePerformDefaultHandling, null)
            }
        }

        TlsTrust.Default -> complete(NSURLSessionAuthChallengePerformDefaultHandling, null)
    }
}

/** The challenge as the pure rule sees it: what is being asked, and for which host. */
internal fun decideFor(policy: TlsPolicy, challenge: NSURLAuthenticationChallenge): TlsTrust =
    TlsTrustRule.decide(
        policy = policy,
        isServerTrust = challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
        challengeHost = challenge.protectionSpace.host,
    )

/**
 * The HTTP client the app dials its gateway with. One client per [de.arjmandi.nanobot.companion.bridge.Companion]
 * so a trust decision, once made for an address, cannot leak into a second gateway.
 */
internal fun gatewayHttpClient(policy: TlsPolicy): HttpClient = HttpClient(Darwin) {
    engine {
        handleChallenge(iosChallengeHandler(policy))
    }
    install(WebSockets)
}
