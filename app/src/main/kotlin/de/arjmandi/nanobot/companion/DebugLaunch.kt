package de.arjmandi.nanobot.companion

import android.content.Intent
import de.arjmandi.nanobot.companion.connect.CredentialsStoring

/**
 * A launch that already knows where the gateway is (DEBUG only), the Android twin of iOS's
 * `Container.forLaunch` live-session path: the address and the key arrive as intent extras, so a
 * live instrument never types a secret into a log. Release builds read nothing and prefill nothing.
 */
data class DebugLaunch(
    val address: String? = null,
    val secret: String? = null,
    val autoConnect: Boolean = false,
)

object DebugLaunchReader {

    private const val URL = "nanobot.url"
    private const val SECRET = "nanobot.secret"
    private const val CONNECT = "nanobot.connect"

    fun read(intent: Intent?): DebugLaunch {
        if (intent == null) return DebugLaunch()
        return DebugLaunch(
            address = intent.getStringExtra(URL),
            secret = intent.getStringExtra(SECRET),
            autoConnect = intent.getBooleanExtra(CONNECT, false),
        )
    }
}

/**
 * The credentials a live launch uses: real behaviour over the values the intent carried, and no
 * persistence — a test run must not leave a gateway's key in this device's store.
 */
class LaunchCredentials(
    override var address: String?,
    override var storedKey: String?,
    override var allowUnverifiedCertificate: Boolean,
) : CredentialsStoring {

    override fun remember(address: String, key: String, allowUnverifiedCertificate: Boolean) {
        this.address = address
        this.storedKey = key
        this.allowUnverifiedCertificate = allowUnverifiedCertificate
    }

    override fun rememberTrustDecision(allowed: Boolean) {
        allowUnverifiedCertificate = allowed
    }

    override fun forget() {
        address = null
        storedKey = null
        allowUnverifiedCertificate = false
    }
}
