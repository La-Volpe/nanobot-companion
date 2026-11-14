package de.arjmandi.nanobot.companion.platform

import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSBundle

/**
 * The iOS app's composition root. The transport seam (HTTP engine and TLS policy)
 * arrives with C3.2, where its shape is the TLS decision rather than a guess.
 */
public fun productionDeps(): CoreDeps = CoreDeps(
    clock = systemClock(),
    secureStore = KeychainSecureStore(SECURE_NAMESPACE),
    files = appSupportFileStore(FILE_NAMESPACE),
    appInfo = BundleAppInfo(),
    dispatchers = CoreDispatchers(compute = Dispatchers.Default, main = Dispatchers.Main),
)

/** The Keychain service name and the container subdirectory: both stable across launches. */
internal const val SECURE_NAMESPACE: String = "de.arjmandi.nanobot.companion"
internal const val FILE_NAMESPACE: String = "companion"

private class BundleAppInfo : AppInfo {
    override val version: String =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
            ?: "0.0.0"
    override val build: String =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String) ?: "0"
    override val platform: String = "ios"
}
