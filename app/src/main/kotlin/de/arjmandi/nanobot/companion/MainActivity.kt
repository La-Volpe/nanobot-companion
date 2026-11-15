package de.arjmandi.nanobot.companion

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import de.arjmandi.nanobot.companion.notifications.TaskNoticeDelivery
import de.arjmandi.nanobot.companion.projection.TaskNotice
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

    /** The shell and the one owner of "ask for a new socket"; `onStart` needs both. */
    private lateinit var main: MainViewModel
    private lateinit var recovery: ConnectionRecovery

    /** The phone's half of a task notice: post what the core allowed, remember what it refused. */
    private lateinit var notifier: AndroidNotifier
    private lateinit var delivery: TaskNoticeDelivery

    /** The core's last allowed notice, kept so a granted permission can post it on the way back. */
    private var noticeToPost: TaskNotice? = null

    /** The one object the screens read from, kept so `onStart` can report the foreground. */
    private lateinit var holder: CoreHolder

    /** A tap that arrived before there was a shell to open the chat on (a cold launch). */
    private var pendingChatId: String? = null
    private var askingForPermission = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            askingForPermission = false
            // The dialog's answer is the answer: a second ask would be a dialog Android refuses
            // to draw, so from here the offer is the system's own screen (`NotificationPrompt`).
            notifier.recordAsked(granted)
            delivery.permissionAnswered(granted)
            if (granted) noticeToPost?.let { delivery.deliver(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        holder = CoreHolder(scope) { address, secret, allowUnverified ->
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
        main = MainViewModel(holder) { chatId ->
            // A chat the core cannot name yet (no gateway, no greeting) still gets a model: the
            // screen shows an empty thread and an idle composer rather than nothing.
            holder.chatSession(chatId) ?: ChatSession(
                chat = EmptyChatDriver(),
                composer = EmptyComposerDriver(),
                snapshots = MutableStateFlow(ComposerSnapshot()),
            )
        }

        notifier = AndroidNotifier(applicationContext)
        delivery = TaskNoticeDelivery(notifier)

        // Settings is built after the shell because its one way out is the shell's own disconnect.
        val settings = SettingsViewModel(
            credentials = credentials,
            notifier = notifier,
            preferences = SharedPreferencesSettings(getSharedPreferences(SETTINGS_PREFERENCES, MODE_PRIVATE)),
            disconnectGateway = { main.disconnect() },
        )
        main.attachSettings(settings)

        // The banner's "Try again" and the return to the foreground are the same reconnect, so
        // they are the same object: one attempt at a time, and only on a socket that was up.
        recovery = ConnectionRecovery(reconnect = { holder.reconnect() }, scope = scope)
        main.attachRecovery(recovery)
        recovery.reload = {
            // The reads run on the api_token the new handshake minted, and the new socket has
            // not been told about the chat on screen.
            holder.refreshSessions()
            main.reattachChatOnScreen()
        }

        // One owner for the connection's view: the holder emits, the shell pushes it to the states.
        scope.launch {
            holder.connection.collect { view ->
                if (view.status == de.arjmandi.nanobot.companion.core.ConnectionStatus.Open) {
                    // A tap that arrived on a cold launch waited for exactly this.
                    pendingChatId?.let { chatId ->
                        pendingChatId = null
                        scope.launch { main.openNotifiedChat(chatId) }
                    }
                }
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

        // One notice in, at most one banner out: the core decided whether the frame deserved
        // one, this only decides whether the phone can show it right now.
        scope.launch {
            holder.taskNotices.collect { notice ->
                noticeToPost = notice
                delivery.deliver(notice)
                askForNotificationPermission()
            }
        }

        // A banner's tap names the chat it came from; a cold launch waits for the socket to be
        // ready, because there is no shell to open a chat on before that.
        consumeNotificationIntent(intent)

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
                // A device that remembers a gateway reopens it here — the key is in the
                // encrypted store, so asking for it again would be theatre.
                LaunchedEffect(launch.autoConnect) {
                    val remembered = !credentials.address.isNullOrBlank() &&
                        !credentials.storedKey.isNullOrBlank()
                    if (launch.autoConnect || remembered) connect.connect()
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

    companion object {
        /** The chat a banner belongs to, carried by the tap's intent and consumed once. */
        const val EXTRA_CHAT_ID: String = "de.arjmandi.nanobot.companion.CHAT_ID"

        /** Appearance's two preferences: not secrets, and read before any key exists. */
        private const val SETTINGS_PREFERENCES: String = "de.arjmandi.nanobot.companion.settings"
    }

    /**
     * Back from the background: Android keeps the process and kills the socket, so this is where a
     * chat client earns its keep. The banner's own states report the progress; nothing here
     * decides what a failure meant.
     */
    override fun onStart() {
        super.onStart()
        // In front of the user again: the chat on screen is announced by the thread itself, so a
        // banner for it would be the app saying what the app is already showing.
        holder.setForeground(true)
        if (!main.connected) return
        recovery.resumed(main.connectionView.status, main.connectionView.everConnected)
    }

    override fun onResume() {
        super.onResume()
        // The first return to the app after a banner it could not show is the moment the
        // permission is worth asking for — not launch, and not every frame.
        askForNotificationPermission()
    }

    override fun onStop() {
        holder.setForeground(false)
        super.onStop()
    }

    /** A tap on a banner while the shell is running: the chat it came from, not a restart. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID) ?: return
        // Consumed once: a rotation or a second `onNewIntent` must not reopen a chat nobody tapped.
        intent.removeExtra(EXTRA_CHAT_ID)
        if (main.connected) {
            scope.launch { main.openNotifiedChat(chatId) }
        } else {
            pendingChatId = chatId
        }
    }

    private fun askForNotificationPermission() {
        if (askingForPermission || !this::delivery.isInitialized) return
        if (!delivery.shouldAskPermission) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        askingForPermission = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        // One owner per socket: the activity ending ends the client the core was dialling with.
        scope.cancel()
        super.onDestroy()
    }
}
