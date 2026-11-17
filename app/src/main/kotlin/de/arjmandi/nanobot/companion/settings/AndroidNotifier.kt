package de.arjmandi.nanobot.companion.settings

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.arjmandi.nanobot.companion.MainActivity
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.notifications.BannerPosting
import de.arjmandi.nanobot.companion.projection.NotificationPermission
import de.arjmandi.nanobot.companion.projection.TaskNotice
import de.arjmandi.nanobot.companion.projection.TaskOutcome

/**
 * A local notification, which is the only kind the gateway can produce. The channel
 * is created once with the app's own name and no importance tricks: the design promises an alert
 * while the app is away, not a push service.
 *
 * A banner names no conversation text, no chat title and no model error, which is the WebUI's own
 * rule for a lock screen (`lib/browser-notifications.ts`). The chat it belongs to travels in the
 * intent, not in the words.
 */
class AndroidNotifier(private val context: Context) : Notifying, BannerPosting {

    /**
     * The permission as the app can tell it. API 33 asks; below that the app is allowed unless the
     * user turned notifications off, and a dialog was never shown at all, which is why the
     * "asked" flag is kept (a refusal must not be re-asked, `NotificationPrompt`).
     */
    override fun permission(): NotificationPermission {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return NotificationPermission.Granted
        }
        val asks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val granted = asks && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return NotificationPermission.Granted
        return if (asks && !askedBefore()) NotificationPermission.NotAsked else NotificationPermission.Denied
    }

    /** The dialog answer, recorded so a second ask is never attempted. */
    override fun recordAsked(granted: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ASKED, true).apply()
    }

    private fun askedBefore(): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    override suspend fun requestAuthorisation(): Boolean = permission() == NotificationPermission.Granted

    override suspend fun send(title: String, body: String) {
        ensureChannel()
        post(id = TEST_ID, title = title, body = body, chatId = null)
    }

    /** One settled turn or recovery, on the phone, for the chat it happened in. */
    override fun post(notice: TaskNotice): Boolean {
        ensureChannel()
        val title = context.getString(
            when (notice.outcome) {
                TaskOutcome.Completed -> R.string.notice_completed
                TaskOutcome.Failed -> R.string.notice_failed
                TaskOutcome.Interrupted -> R.string.notice_interrupted
                TaskOutcome.NeedsAttention -> R.string.notice_needs_attention
            },
        )
        return post(
            id = notificationId(notice.chatId),
            title = title,
            body = context.getString(R.string.notice_body),
            chatId = notice.chatId,
        )
    }

    /** The chat was opened by hand, so its banner has nothing left to say. */
    fun cancel(chatId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(chatId))
    }

    /** Where a user turns the app's alerts back on, once the dialog has stopped being an option. */
    override fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            // Below API 26 there is no per-app notification screen; the app's own page is next.
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(fallback) }
        }
    }

    /** The reader's own switch, read at post time so a change applies to the next banner. */
    private fun soundEnabled(): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SettingsViewModel.KEY_NOTIFICATION_SOUND, true)

    private fun post(id: Int, title: String, body: String, chatId: String?): Boolean {
        // The permission is the screen's business: a post without it throws, so this is the one
        // place the notifier checks rather than pretending it delivered.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent(chatId))
            .setAutoCancel(true)
            // `setSilent` is the per-notification switch the channel leaves to the app: the
            // channel's importance is the reader's system setting, this is the app's own.
            .setSilent(!soundEnabled())
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        }.getOrDefault(false)
    }

    /**
     * A tap opens the chat it came from on the shell that is already running: one activity, the
     * chat id as an extra, and `MainActivity` treats a notification like any other arrival.
     */
    private fun tapIntent(chatId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (chatId != null) putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
        }
        return PendingIntent.getActivity(
            context,
            chatId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(chatId: String): Int = chatId.hashCode()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Task alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A task finished, failed, or needs your attention."
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "de.arjmandi.nanobot.companion.alerts"
        const val TEST_ID = 1
        private const val PREFERENCES = "de.arjmandi.nanobot.companion.settings"
        private const val KEY_ASKED = "notification.asked"
    }
}

/** Preferences: `SharedPreferences` in the app, a map in a test. */
class SharedPreferencesSettings(private val preferences: android.content.SharedPreferences) : SettingsStoring {
    override fun string(key: String): String? = preferences.getString(key, null)

    override fun set(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun flag(key: String): Boolean? =
        if (preferences.contains(key)) preferences.getBoolean(key, true) else null

    override fun set(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }
}
