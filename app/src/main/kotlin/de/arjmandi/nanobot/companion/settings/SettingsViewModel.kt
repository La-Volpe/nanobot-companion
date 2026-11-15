package de.arjmandi.nanobot.companion.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Settings (plan.md A7, the Android twin of iOS's `SettingsViewModel`): what Overview may say, the
 * notification switches and their single honest promise, the appearance preference, and the way
 * out. Sectioned lists and M3 switches are the design's Android column; the rules are all here.
 */
class SettingsViewModel(
    private val credentials: SettingsCredentials,
    private val notifier: Notifying,
    private val preferences: SettingsStoring,
    private val disconnectGateway: () -> Unit,
) {

    var snapshot: SettingsSnapshot by mutableStateOf(SettingsSnapshot())
        private set

    /** Whether Overview's read has answered: a skeleton, the rows, or the failure sentence. */
    var overview: OverviewState by mutableStateOf(OverviewState.Loading)
        private set

    /** The one sentence an action produced; the screen turns it into its string. */
    var notice: SettingsNotice? by mutableStateOf(null)
        private set

    var theme: SettingsTheme by mutableStateOf(
        SettingsTheme.entries.firstOrNull { it.name.equals(preferences.string(KEY_THEME), ignoreCase = true) }
            ?: SettingsTheme.System,
    )
        private set

    var readingSize: ReadingSize by mutableStateOf(
        ReadingSize.entries.firstOrNull { it.name.equals(preferences.string(KEY_READING_SIZE), ignoreCase = true) }
            ?: ReadingSize.Medium,
    )
        private set

    /** What the phone says about this app's notifications, read when the screen opens. */
    var permission: de.arjmandi.nanobot.companion.projection.NotificationPermission by
        mutableStateOf(de.arjmandi.nanobot.companion.projection.NotificationPermission.NotAsked)
        private set

    var notificationToggles: List<SettingsToggle> by mutableStateOf(
        listOf(
            SettingsToggle("Task finished", isOn = true),
            SettingsToggle("Task failed", isOn = true),
            SettingsToggle("Needs attention", isOn = false),
        ),
    )
        private set

    val sections: List<String> = listOf("Gateway", "Overview", "Notifications", "Appearance")

    val gatewaySubtitle: String
        get() = credentials.address?.takeIf { it.isNotEmpty() }?.let { "$it · connected" } ?: "No gateway"

    val disconnectTitle: String = "Disconnect gateway?"

    val disconnectMessage: String =
        "The app forgets this address and key. Nothing on the gateway is deleted, and you can reconnect any time."

    val disconnectActions: List<String> = listOf("Cancel", "Disconnect")

    /**
     * The one promise the Notifications screen can keep, permanently (design README): the gateway
     * has no push transport, so the copy says so instead of hinting at a later version.
     */
    val notificationsCopy: String =
        "Alerts arrive while you are away: a local notification when a turn finishes, fails or " +
            "stops, and when nanobot needs an answer. The chat you are looking at is never " +
            "announced. The gateway has no push service and none is planned, so a closed app " +
            "stays quiet instead of promising an alert it cannot deliver."

    /** The state the screen names, and the one action it offers for it. */
    fun refreshPermission() {
        permission = notifier.permission()
    }

    fun openNotificationSettings() {
        notifier.openSystemSettings()
    }

    /** The system dialog's answer: the state the row names follows it, whichever way it went. */
    fun answeredPermission(granted: Boolean) {
        notifier.recordAsked(granted)
        refreshPermission()
    }

    fun update(snapshot: SettingsSnapshot) {
        this.snapshot = snapshot
    }

    /**
     * The Overview read. Null is "did not answer", which the section draws as a sentence rather
     * than as rows with blanks where the gateway's answers belong.
     */
    suspend fun refresh(load: suspend () -> SettingsSnapshot?) {
        overview = OverviewState.Loading
        val loaded = load()
        if (loaded == null) {
            overview = OverviewState.Failed
            return
        }
        snapshot = loaded
        overview = OverviewState.Loaded
    }

    val overviewRows: List<SettingsRow>
        get() = buildList {
            add(
                SettingsRow(
                    label = "nanobot Gateway",
                    detail = snapshot.commit?.let { "Commit $it" },
                    value = snapshot.version,
                ),
            )
            snapshot.os?.let { os ->
                add(SettingsRow(label = "Server", detail = snapshot.pythonVersion?.let { "Python $it" }, value = os))
            }
            add(
                SettingsRow(
                    label = "Current model",
                    detail = snapshot.modelPreset?.let { "Preset $it" },
                    value = snapshot.modelName,
                ),
            )
            snapshot.contextWindowTokens?.let { window ->
                add(SettingsRow(label = "Context window", detail = null, value = "${window / 1000}k tokens"))
            }
            add(
                SettingsRow(
                    label = "Transcription",
                    detail = snapshot.transcriptionProvider,
                    value = snapshot.transcriptionModel?.replaceFirstChar { it.uppercase() },
                ),
            )
            add(SettingsRow(label = "Model presets", detail = null, value = "${snapshot.presets.size}"))
            // No uptime row: the gateway's settings payload has no uptime field, and the design says
            // so rather than guessing one from a process start time this client cannot see.
            add(SettingsRow(label = "Address", detail = null, value = credentials.address ?: "—"))
        }

    fun setToggle(title: String, on: Boolean) {
        notificationToggles = notificationToggles.map { if (it.title == title) it.copy(isOn = on) else it }
    }

    /** The screen's proof that it can keep its promise: a real local notification (D8). */
    suspend fun sendTestNotification() {
        notice = null
        if (!notifier.requestAuthorisation()) {
            notice = SettingsNotice.NotificationsOff
            return
        }
        notifier.send(title = "Task completed", body = "This is a test notification. Tap it to open the chat.")
        notice = SettingsNotice.TestSent
    }

    fun chooseTheme(chosen: SettingsTheme) {
        theme = chosen
        preferences.set(KEY_THEME, chosen.name.lowercase())
    }

    fun chooseReadingSize(chosen: ReadingSize) {
        readingSize = chosen
        preferences.set(KEY_READING_SIZE, chosen.name.lowercase())
    }

    /** The way out: the key and the address go, the shell closes the socket, onboarding returns. */
    fun disconnect() {
        credentials.forget()
        disconnectGateway()
        notice = SettingsNotice.Disconnected
    }

    fun clearNotice() {
        notice = null
    }

    private companion object {
        const val KEY_THEME = "settings.theme"
        const val KEY_READING_SIZE = "settings.readingSize"
    }
}
