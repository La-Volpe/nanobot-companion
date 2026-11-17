package de.arjmandi.nanobot.companion.settings

import de.arjmandi.nanobot.companion.projection.NoticeCategory
import de.arjmandi.nanobot.companion.projection.NoticeSwitches

/**
 * Settings, as the app's own shapes. The bridge fills [SettingsSnapshot] from the
 * gateway's settings read; nothing here invents a field the gateway does not send.
 */

/**
 * The gateway facts Overview shows. Deliberately no uptime: the payload has none, and deliberately
 * no model either: `agent.model` is what the gateway is configured for, so Overview names the model
 * in use instead: the open chat's composer ([ChatModelInUse]).
 */
data class SettingsSnapshot(
    val version: String? = null,
    val commit: String? = null,
    val os: String? = null,
    val pythonVersion: String? = null,
    val transcriptionProvider: String? = null,
    val transcriptionModel: String? = null,
    val presets: List<String> = emptyList(),
    /** The `/api/settings/usage` read drawn as the card's own numbers; null is "not read". */
    val usage: UsageSnapshot? = null,
)

/**
 * Overview's token card: the headline, the gateway's own 30-day counts, and the daily strip.
 * The window, the gap-filling and the compact copy are the core's (`UsageWindow`); this is the value
 * the card draws.
 */
data class UsageSnapshot(
    /** `505K`, `1.5M`: `formatCompactTokenCount`, the WebUI's own headline. */
    val headline: String,
    val totalTokens: Long,
    val requests: Int,
    /** `62` for 62%; null when the gateway had no denominator, which is not "0%". */
    val cacheHitPercent: Int?,
    /** Oldest first, always 30: a day the gateway did not report is a zero bar. */
    val bars: List<UsageBarView>,
    val hasUsage: Boolean,
) {
    data class UsageBarView(
        val date: String,
        /** 0…1 of the window's peak, computed by the core so both phones draw one chart. */
        val height: Float,
    )
}

/**
 * The model the open chat is running, as the composer row knows it: the chip's name and
 * preset and the ring's denominator. The core resolves and words it (`ModelInUse`), so both phones'
 * rows agree; null in [SettingsViewModel.modelInUse] is "no chat open", not an empty model.
 */
data class ChatModelInUse(
    val name: String? = null,
    val preset: String? = null,
    /** The row's second line, already worded by the core: `Preset primary`. */
    val presetLabel: String? = null,
    /** `1M`, `200K`, `32.8K`: the core's token copy, without the unit the row adds. */
    val windowLabel: String? = null,
)

/**
 * Whether Overview's read has answered. Before it does the section used to draw five rows with no
 * values, which reads like the gateway answering "nothing" to every question.
 */
enum class OverviewState { Loading, Loaded, Failed }

/** One row of a sectioned list: the label, an optional second line, and the value on the right. */
data class SettingsRow(val label: String, val detail: String?, val value: String?)

/**
 * One notification switch: the category it gates, the words on the row, and the reader's answer.
 * The category carries the store key and the default, so the row that is drawn and the flag
 * the poster checks cannot be two different things.
 */
data class SettingsToggle(
    val category: NoticeCategory,
    val title: String,
    val isOn: Boolean,
)

/**
 * The reader's switches as the poster reads them at post time: the store is the phone's, the
 * keys and defaults are the core's, and one read gives all three so a notice cannot be checked
 * against a category whose key nobody wrote.
 */
fun SettingsStoring.noticeSwitches(): NoticeSwitches {
    // Each default is the core's own (`onByDefault`), so a switch nobody touched and a poster that
    // never found the key agree about what a first launch does.
    fun of(category: NoticeCategory): Boolean = flag(category.preferenceKey) ?: category.onByDefault
    return NoticeSwitches(
        finished = of(NoticeCategory.Finished),
        failedOrStopped = of(NoticeCategory.FailedOrStopped),
        needsAttention = of(NoticeCategory.NeedsAttention),
    )
}

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

/** The notifier, as a seam: `NotificationManager` in the app, a recorder in a test. */
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
