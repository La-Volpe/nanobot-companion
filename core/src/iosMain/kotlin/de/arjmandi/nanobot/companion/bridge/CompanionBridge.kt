package de.arjmandi.nanobot.companion.bridge

import de.arjmandi.nanobot.companion.facade.GATEWAY_SECRET_KEY
import de.arjmandi.nanobot.companion.facade.GatewayProfile
import de.arjmandi.nanobot.companion.facade.NanobotCore
import de.arjmandi.nanobot.companion.platform.gatewayHttpClient
import de.arjmandi.nanobot.companion.platform.productionDeps
import de.arjmandi.nanobot.companion.platform.TlsPolicy
import io.ktor.client.HttpClient

/**
 * The app-level object the SwiftUI layer holds (plan.md C8.2). It exists for two reasons:
 * the facade's constructor wants an `HttpClient`, which no Swift file should have to build,
 * and the client needs an owner that ends it — a `NanobotCore` outlives neither.
 *
 * The stored key is here rather than in Swift for the same reason [productionDeps] is in
 * Kotlin: the Keychain is one implementation (plan.md D5), and a second one in Swift would
 * be a second thing to test.
 */
public class Companion internal constructor(
    public val core: NanobotCore,
    private val http: HttpClient,
) {

    /** Ends the HTTP client, and with it every socket the core opened through it. */
    public fun close() {
        http.close()
    }
}

/**
 * The stored gateway key (plan.md D5). It is a top-level function rather than part of
 * [Companion] because onboarding has to read the key before there is anything to connect to:
 * the launch screen decides between "open this gateway" and "ask for a key" by asking this.
 */
public fun storeGatewaySecret(secret: String) {
    productionDeps().secureStore.put(GATEWAY_SECRET_KEY, secret)
}

public fun readGatewaySecret(): String? = productionDeps().secureStore.get(GATEWAY_SECRET_KEY)

/** Settings → Gateway: the address goes with it, so the screen returns to onboarding. */
public fun forgetGatewaySecret() {
    productionDeps().secureStore.remove(GATEWAY_SECRET_KEY)
}

/**
 * Builds the whole iOS stack from the three things onboarding collects: the address, the key,
 * and whether the user turned on the unverified-certificate switch for this gateway (C3.2).
 *
 * The `url` is kept as the user typed it; `GatewayProfile` trims the trailing slash where it
 * builds a request. The trust policy is built from the same string, so the host the certificate
 * must match is the host the user typed and no other (D21, [TlsTrustRule]).
 */
public fun openCompanion(
    baseUrl: String,
    secret: String,
    allowUnverifiedCertificate: Boolean,
): Companion {
    val policy = TlsPolicy.forGateway(baseUrl, trustSelfSignedCertificates = allowUnverifiedCertificate)
    val http = gatewayHttpClient(policy)
    val core = NanobotCore(GatewayProfile(url = baseUrl, secret = secret), productionDeps(), http)
    return Companion(core, http)
}
