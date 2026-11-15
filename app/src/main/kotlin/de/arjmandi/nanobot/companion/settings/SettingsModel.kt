package de.arjmandi.nanobot.companion.settings

/**
 * Settings (plan.md A7), as the app's own shapes. The bridge fills [SettingsSnapshot] from the
 * gateway's settings read; nothing here invents a field the gateway does not send.
 */

/** The gateway facts Overview shows. Deliberately no uptime: the payload has none. */
data class SettingsSnapshot(
    val version: String? = null,
    val commit: String? = null,
    val os: String? = null,
    val pythonVersion: String? = null,
    val modelName: String? = null,
    val modelPreset: String? = null,
    val provider: String? = null,
    val contextWindowTokens: Int? = null,
    /** The window as the core's token copy prints it (`1M`, `200K`, `32.8K`): T36. */
    val contextWindowLabel: String? = null,
    val transcriptionProvider: String? = null,
    val transcriptionModel: String? = null,
    val presets: List<String> = emptyList(),
)

/**
 * Whether Overview's read has answered. Before it does the section used to draw five rows with no
 * values, which reads like the gateway answering "nothing" to every question.
 */
enum class OverviewState { Loading, Loaded, Failed }

/** One row of a sectioned list: the label, an optional second line, and the value on the right. */
data class SettingsRow(val label: String, val detail: String?, val value: String?)

/** One notification switch; the three the design draws. */
data class SettingsToggle(val title: String, val isOn: Boolean)

/** The sentences a Settings action can produce; the screen turns each into its string. */
enum class SettingsNotice { NotificationsOff, TestSent, Disconnected }

enum class SettingsTheme { System, Light, Dark }

enum class ReadingSize { Small, Medium, Large }

/**
 * The reading scale (design README): prose, code and the composer follow this number, navigation
 * does not. Android's medium is 16 sp, which is the app's reading size already.
 */
object ReadingScale {
    fun size(size: ReadingSize): Int = when (size) {
        ReadingSize.Small -> 15
        ReadingSize.Medium -> 16
        ReadingSize.Large -> 18
    }
}

/** Where the gateway's address and key live, as Settings needs to forget them. */
interface SettingsCredentials {
    val address: String?

    fun forget()
}

/** Where preferences live: `SharedPreferences` in the app, a map in a test. */
interface SettingsStoring {
    fun string(key: String): String?
    fun set(key: String, value: String)

    /** An on/off preference; null when nobody has chosen yet, which is not the same as "off". */
    fun flag(key: String): Boolean?
    fun set(key: String, value: Boolean)
}

/** The notifier, as a seam: `NotificationManager` in the app, a recorder in a test (D8). */
interface Notifying {
    suspend fun requestAuthorisation(): Boolean
    suspend fun send(title: String, body: String)

    /** The system permission as the screen shows it; *when* to ask is the core's `NotificationPrompt`. */
    fun permission(): de.arjmandi.nanobot.companion.projection.NotificationPermission

    /** Where a user turns alerts back on after "no": the platform's own screen, not a second dialog. */
    fun openSystemSettings()

    /** The dialog was shown: the answer stands, and asking again is not an option Android offers. */
    fun recordAsked(granted: Boolean)
}
