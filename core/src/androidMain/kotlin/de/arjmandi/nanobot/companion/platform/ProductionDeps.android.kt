package de.arjmandi.nanobot.companion.platform

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers

/** The Android app's composition root; the transport seam arrives with C3.2. */
public fun productionDeps(context: Context): CoreDeps = CoreDeps(
    clock = systemClock(),
    secureStore = androidSecureStore(context, SECURE_NAMESPACE),
    files = appSupportFileStore(context, FILE_NAMESPACE),
    appInfo = PackageAppInfo(context),
    dispatchers = CoreDispatchers(compute = Dispatchers.Default, main = Dispatchers.Main),
)

internal const val SECURE_NAMESPACE: String = "de.arjmandi.nanobot.companion"
internal const val FILE_NAMESPACE: String = "companion"

private class PackageAppInfo(private val context: Context) : AppInfo {

    override val version: String by lazy {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: "0.0.0"
    }

    override val build: String by lazy {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toString()
    }

    override val platform: String = "android"
}
