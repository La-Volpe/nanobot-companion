package de.arjmandi.nanobot.companion.bridge

import android.content.Context
import de.arjmandi.nanobot.companion.facade.GATEWAY_SECRET_KEY
import de.arjmandi.nanobot.companion.facade.GatewayProfile
import de.arjmandi.nanobot.companion.facade.NanobotCore
import de.arjmandi.nanobot.companion.platform.TlsPolicy
import de.arjmandi.nanobot.companion.platform.androidHttpClient
import de.arjmandi.nanobot.companion.platform.productionDeps
import io.ktor.client.HttpClient

/**
 * The app-level object the Android layer holds (the twin of the iOS `Companion`).
 * It exists for the same two reasons: the facade's constructor wants an `HttpClient`, which no
 * screen should build, and the client needs an owner that ends it, since a `NanobotCore` outlives
 * neither.
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
 * Builds the whole Android stack from the three things onboarding collects: the address, the key,
 * and whether the user turned on the unverified-certificate switch for this gateway.
 *
 * The `context` is here because the platform stores are: the encrypted preferences this app keeps
 * its key in and the app-support directory a thread cache is written to.
 */
public fun openCompanion(
    context: Context,
    baseUrl: String,
    secret: String,
    allowUnverifiedCertificate: Boolean,
): Companion {
    val policy = TlsPolicy.forGateway(baseUrl, trustSelfSignedCertificates = allowUnverifiedCertificate)
    val http = androidHttpClient(policy)
    val core = NanobotCore(GatewayProfile(url = baseUrl, secret = secret), productionDeps(context), http)
    return Companion(core, http)
}

/** The stored gateway key, kept here rather than in the app for the same reason [productionDeps] is. */
public fun storeGatewaySecret(context: Context, secret: String) {
    productionDeps(context).secureStore.put(GATEWAY_SECRET_KEY, secret)
}

public fun readGatewaySecret(context: Context): String? = productionDeps(context).secureStore.get(GATEWAY_SECRET_KEY)

/** Settings → Gateway: the address goes with it, so the screen returns to onboarding. */
public fun forgetGatewaySecret(context: Context) {
    productionDeps(context).secureStore.remove(GATEWAY_SECRET_KEY)
}
