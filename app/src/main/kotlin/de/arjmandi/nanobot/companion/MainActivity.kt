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
import de.arjmandi.nanobot.companion.composer.ComposerSnapshot
import de.arjmandi.nanobot.companion.composer.EmptyComposerDriver
import de.arjmandi.nanobot.companion.core.ChatSession
import de.arjmandi.nanobot.companion.settings.AndroidNotifier
import de.arjmandi.nanobot.companion.settings.SettingsViewModel
import de.arjmandi.nanobot.companion.settings.SharedPreferencesSettings
import kotlinx.coroutines.flow.MutableStateFlow
import de.arjmandi.nanobot.companion.connect.ConnectViewModel
import de.arjmandi.nanobot.companion.connect.CredentialsStoring
import de.arjmandi.nanobot.companion.core.CoreHolder
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import de.arjmandi.nanobot.companion.ui.RootScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
            // screen shows an empty thread and an idle composer rather than nothing.
            holder.chatSession(chatId) ?: ChatSession(
                chat = EmptyChatDriver(),
                composer = EmptyComposerDriver(),
                snapshots = MutableStateFlow(ComposerSnapshot()),
            )
        }

        // Settings is built after the shell because its one way out is the shell's own disconnect.
        val settings = SettingsViewModel(
            credentials = credentials,
            notifier = AndroidNotifier(applicationContext),
            preferences = SharedPreferencesSettings(getSharedPreferences(SETTINGS_PREFERENCES, MODE_PRIVATE)),
            disconnectGateway = { main.disconnect() },
        )
        main.attachSettings(settings)

        // One owner for the connection's view: the holder emits, the shell pushes it to the states.
        scope.launch {
            holder.connection.collect { view ->
                main.updateConnection(
                    de.arjmandi.nanobot.companion.states.ConnectionView(
                        status = when (view.status) {
                            de.arjmandi.nanobot.companion.core.ConnectionStatus.Idle ->
                                de.arjmandi.nanobot.companion.states.ConnectionStatus.Idle
                            de.arjmandi.nanobot.companion.core.ConnectionStatus.Connecting ->
                                de.arjmandi.nanobot.companion.states.ConnectionStatus.Connecting
                            de.arjmandi.nanobot.companion.core.ConnectionStatus.Open ->
                                de.arjmandi.nanobot.companion.states.ConnectionStatus.Open
                            de.arjmandi.nanobot.companion.core.ConnectionStatus.Offline ->
                                de.arjmandi.nanobot.companion.states.ConnectionStatus.Offline
                            de.arjmandi.nanobot.companion.core.ConnectionStatus.Failed ->
                                de.arjmandi.nanobot.companion.states.ConnectionStatus.Failed
                        },
                        everConnected = view.everConnected,
                        failure = view.failure,
                        detail = view.detail,
                    ),
                )
            }
        }

        setContent {
            // Appearance is the user's: the theme picks the scheme, the reading size scales prose.
            val dark = when (settings.theme) {
                de.arjmandi.nanobot.companion.settings.SettingsTheme.Dark -> true
                de.arjmandi.nanobot.companion.settings.SettingsTheme.Light -> false
                de.arjmandi.nanobot.companion.settings.SettingsTheme.System ->
                    androidx.compose.foundation.isSystemInDarkTheme()
            }
            NanobotTheme(
                dark = dark,
                readingScale = de.arjmandi.nanobot.companion.settings.ReadingScale.size(settings.readingSize) / 16f,
            ) {
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

    private companion object {
        /** Appearance's two preferences: not secrets, and read before any key exists. */
        const val SETTINGS_PREFERENCES = "de.arjmandi.nanobot.companion.settings"
    }

    override fun onDestroy() {
        // One owner per socket: the activity ending ends the client the core was dialling with.
        scope.cancel()
        super.onDestroy()
    }
}
