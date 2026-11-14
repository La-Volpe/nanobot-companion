package de.arjmandi.nanobot.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import de.arjmandi.nanobot.companion.bridge.openCompanion
import de.arjmandi.nanobot.companion.chat.EmptyChatDriver
import de.arjmandi.nanobot.companion.connect.ConnectViewModel
import de.arjmandi.nanobot.companion.connect.CredentialsStoring
import de.arjmandi.nanobot.companion.core.CoreHolder
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import de.arjmandi.nanobot.companion.ui.RootScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The composition root (plan.md A1, the Android twin of iOS's `Container`): one holder, one
 * connect model, one shell model, and the seam that builds a stack for a gateway. A test swaps the
 * seam; nothing else in the app knows a `NanobotCore` exists.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val holder = CoreHolder(scope) { address, secret, allowUnverified ->
            openCompanion(applicationContext, address, secret, allowUnverified)
        }
        // A live launch (DEBUG) carries its address and key, so a running instrument never types
        // a secret into a log; every other launch reads the encrypted store.
        val launch = if (BuildConfig.DEBUG) DebugLaunchReader.read(intent) else DebugLaunch()
        val credentials: CredentialsStoring = if (launch.address != null && launch.secret != null) {
            LaunchCredentials(launch.address, launch.secret, allowUnverifiedCertificate = false)
        } else {
            StoredCredentials(
                applicationContext,
                getSharedPreferences(StoredCredentials.PREFERENCES, MODE_PRIVATE),
            )
        }
        val connect = ConnectViewModel(holder, credentials, prefillKey = launch.secret)
        val main = MainViewModel(holder) { chatId ->
            // A chat the core cannot name yet (no gateway, no greeting) still gets a model: the
            // screen shows an empty thread rather than nothing.
            holder.chatDriver(chatId) ?: EmptyChatDriver()
        }

        setContent {
            NanobotTheme {
                // The launch environment is the live instrument's: one press, or none at all.
                LaunchedEffect(launch.autoConnect) {
                    if (launch.autoConnect) connect.connect()
                }
                RootScreen(
                    connect = connect,
                    main = main,
                    holder = holder,
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                )
            }
        }
    }

    override fun onDestroy() {
        // One owner per socket: the activity ending ends the client the core was dialling with.
        scope.cancel()
        super.onDestroy()
    }
}
