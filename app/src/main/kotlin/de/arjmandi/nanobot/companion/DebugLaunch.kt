package de.arjmandi.nanobot.companion

import android.content.Intent

/**
 * A launch that already knows where the gateway is (DEBUG only), the Android twin of iOS's
 * `Container.forLaunch` live-session path: the address and the key arrive as intent extras, so a
 * live instrument never types a secret into a log. Release builds read nothing and prefill nothing.
 *
 * What arrives is a prefill of the one encrypted store, never a store of its own: a connect made
 * through a live launch has to survive the next one, because the icon on the home screen carries no
 * extras. The extras are read in `onCreate`, so a script must stop the app first
 * (`adb shell am force-stop de.arjmandi.nanobot.companion`). A `singleTop` relaunch of a running
 * activity has its intent delivered to `onNewIntent`, which is the banner tap's path, not a new
 * launch's.
 */
data class DebugLaunch(
    val address: String? = null,
    val secret: String? = null,
)

object DebugLaunchReader {

    private const val URL = "nanobot.url"
    private const val SECRET = "nanobot.secret"

    fun read(intent: Intent?): DebugLaunch {
        if (intent == null) return DebugLaunch()
        return DebugLaunch(
            address = intent.getStringExtra(URL),
            secret = intent.getStringExtra(SECRET),
        )
    }
}
