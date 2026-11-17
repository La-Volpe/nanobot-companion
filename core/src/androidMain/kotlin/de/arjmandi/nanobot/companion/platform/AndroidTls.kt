package de.arjmandi.nanobot.companion.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The Android half of the trust decision (the twin of `IosTls.kt`): the
 * platform validates, and the switch only changes what happens when that validation rejects the
 * *anchor*, which is what a self-signed home gateway looks like.
 *
 * The lenient manager is installed only when the user turned the switch on for this gateway, and
 * this client is built for one gateway address (`openCompanion`), so it cannot widen trust for a
 * second host. The hostname check stays OkHttp's: the switch is about the certificate, not the
 * name on it. The handshake itself is not covered by a test here, the same gap iOS has until a
 * local self-signed server exists.
 */
internal fun androidHttpClient(policy: TlsPolicy): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            if (policy.trustSelfSignedCertificates && policy.host != null) {
                val manager = GatewayTrustManager(platformTrustManager())
                sslSocketFactory(
                    SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }.socketFactory,
                    manager,
                )
            }
        }
    }
    install(WebSockets)
}

/** The platform's own manager: every certificate chain is judged exactly as the OS judges it. */
internal fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

/**
 * The platform's decision, then the user's: a chain the OS accepts is accepted unchanged, and one
 * the OS rejects is accepted only because the switch was turned on for this address. An empty or
 * unparseable chain is never accepted, switch or not: there is nothing the user could have meant.
 */
internal class GatewayTrustManager(private val platform: X509TrustManager) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        platform.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            platform.checkServerTrusted(chain, authType)
        } catch (rejected: CertificateException) {
            if (chain.isNullOrEmpty()) throw rejected
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}
