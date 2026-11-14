package de.arjmandi.nanobot.companion.settings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * A local notification, which is the only kind the gateway can produce (plan.md D8). The channel
 * is created once with the app's own name and no importance tricks: the design promises an alert
 * while the app is open, not a push service.
 */
class AndroidNotifier(private val context: Context) : Notifying {

    override suspend fun requestAuthorisation(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    override suspend fun send(title: String, body: String) {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        // The permission is the screen's business: a post without it would throw, so this is the
        // one place the notifier checks rather than pretending it delivered.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

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
        const val NOTIFICATION_ID = 1
    }
}

/** Preferences: `SharedPreferences` in the app, a map in a test. */
class SharedPreferencesSettings(private val preferences: android.content.SharedPreferences) : SettingsStoring {
    override fun string(key: String): String? = preferences.getString(key, null)

    override fun set(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}
