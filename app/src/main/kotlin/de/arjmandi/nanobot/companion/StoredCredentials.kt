package de.arjmandi.nanobot.companion

import android.content.Context
import android.content.SharedPreferences
import de.arjmandi.nanobot.companion.bridge.forgetGatewaySecret
import de.arjmandi.nanobot.companion.bridge.readGatewaySecret
import de.arjmandi.nanobot.companion.bridge.storeGatewaySecret
import de.arjmandi.nanobot.companion.connect.CredentialsStoring

/**
 * Where the gateway's address, key and trust decision live (D17, D47). The key goes to the core's
 * secure store — the same encrypted-preferences implementation `:core` tested through its seam —
 * and the address and the switch go to ordinary preferences, because neither is a secret and both
 * are needed before there is a key to unlock anything with.
 */
class StoredCredentials(private val context: Context, private val preferences: SharedPreferences) : CredentialsStoring {

    override val address: String? get() = preferences.getString(KEY_ADDRESS, null)?.takeIf { it.isNotEmpty() }

    override val storedKey: String? get() = readGatewaySecret(context)

    override val allowUnverifiedCertificate: Boolean get() = preferences.getBoolean(KEY_UNVERIFIED, false)

    override fun remember(address: String, key: String, allowUnverifiedCertificate: Boolean) {
        preferences.edit().putString(KEY_ADDRESS, address).apply()
        rememberTrustDecision(allowUnverifiedCertificate)
        storeGatewaySecret(context, key)
    }

    override fun rememberTrustDecision(allowed: Boolean) {
        preferences.edit().putBoolean(KEY_UNVERIFIED, allowed).apply()
    }

    override fun forget() {
        preferences.edit().remove(KEY_ADDRESS).remove(KEY_UNVERIFIED).apply()
        forgetGatewaySecret(context)
    }

    companion object {
        private const val KEY_ADDRESS = "gateway.address"
        private const val KEY_UNVERIFIED = "gateway.allowUnverifiedCertificate"

        /** One place, so the activity and a test cannot disagree about which file it is. */
        const val PREFERENCES = "de.arjmandi.nanobot.companion.gateway"
    }
}
