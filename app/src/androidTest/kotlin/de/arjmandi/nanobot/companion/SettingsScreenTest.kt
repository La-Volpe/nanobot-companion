package de.arjmandi.nanobot.companion

import de.arjmandi.nanobot.companion.projection.NoticeCategory
import de.arjmandi.nanobot.companion.projection.NotificationPermission

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.settings.ChatModelInUse
import de.arjmandi.nanobot.companion.settings.Notifying
import de.arjmandi.nanobot.companion.settings.ReadingSize
import de.arjmandi.nanobot.companion.settings.SettingsCredentials
import de.arjmandi.nanobot.companion.settings.SettingsScreen
import de.arjmandi.nanobot.companion.settings.SettingsSnapshot
import de.arjmandi.nanobot.companion.settings.SettingsStoring
import de.arjmandi.nanobot.companion.settings.SettingsTheme
import de.arjmandi.nanobot.companion.settings.SettingsViewModel
import de.arjmandi.nanobot.companion.settings.UsageSnapshot
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A7's screens (the Compose half): the four sections, Overview's rows with no uptime line, the
 * notification switches and the local test alert, the appearance choices, and the disconnect that
 * asks first and then forgets the gateway. The rules are `SettingsViewModelTest`'s; this is what
 * only a composition proves.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The notification permission, granted the way a user grants it: without this the screen's
     * honest behaviour on API 33 is to ask first, and a test cannot answer a system dialog.
     */
    @Before
    fun grantNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private class Credentials : SettingsCredentials {
        override var address: String? = "http://10.0.2.2:18765"
        var forgot = 0
        override fun forget() {
            forgot += 1
            address = null
        }
    }

    private class Notifier : Notifying {
        val sent = mutableListOf<String>()
        var state = NotificationPermission.Granted
        var systemSettings = 0

        override suspend fun requestAuthorisation(): Boolean = true
        override suspend fun send(title: String, body: String) {
            sent += title
        }

        override fun permission(): NotificationPermission = state

        override fun openSystemSettings() {
            systemSettings += 1
        }

        override fun recordAsked(granted: Boolean) {
            state = if (granted) NotificationPermission.Granted else NotificationPermission.Denied
        }
    }

    private object Memory : SettingsStoring {
        override fun string(key: String): String? = null
        override fun set(key: String, value: String) = Unit
        override fun flag(key: String): Boolean? = null
        override fun set(key: String, value: Boolean) = Unit
    }

    private fun model(credentials: Credentials = Credentials(), notifier: Notifier = Notifier()) =
        SettingsViewModel(credentials, notifier, Memory, disconnectGateway = {})

    /** The card's numbers as the bridge builds them from a read: one full day, 29 stubs (T44). */
    private fun usage(hasUsage: Boolean = true) = UsageSnapshot(
        headline = if (hasUsage) "505K" else "0",
        totalTokens = if (hasUsage) 505_134 else 0,
        requests = if (hasUsage) 140 else 0,
        cacheHitPercent = if (hasUsage) 89 else null,
        bars = (1..30).map { day ->
            UsageSnapshot.UsageBarView(date = "2026-11-%02d".format(day), height = if (hasUsage && day == 14) 1f else 0f)
        },
        hasUsage = hasUsage,
    )

    private fun screen(
        model: SettingsViewModel,
        snapshot: SettingsSnapshot = SettingsSnapshot(
            version = "0.4.1",
            commit = "28d73b69",
            os = "Darwin",
            pythonVersion = "3.12.9",
            presets = listOf("default", "primary"),
            usage = usage(),
        ),
        modelInUse: ChatModelInUse? = ChatModelInUse(
            name = "glm-5.3-flash:cloud",
            preset = "primary",
            presetLabel = "Preset primary",
            windowLabel = "1M",
        ),
    ) {
        compose.setContent {
            NanobotTheme {
                SettingsScreen(model = model, loadSnapshot = { snapshot }, onClose = {}, modelInUse = modelInUse)
            }
        }
    }

    @Test
    fun overviewShowsTheVersionTheCommitAndNoUptime() {
        screen(model())

        compose.onNodeWithTag("settings.overview.nanobot Gateway").assertIsDisplayed()
        compose.onNodeWithText("Commit 28d73b69").assertIsDisplayed()
        compose.onNodeWithText("1M tokens").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Uptime", substring = true, ignoreCase = true).fetchSemanticsNodes().size,
            "the gateway's payload has no uptime field",
        )
    }

    /**
     * T45: with no chat open there is no model in use, and the row says so instead of printing the
     * gateway's `agent.model`, which is a configuration and not the model answering.
     */
    @Test
    fun withNoChatOpenTheModelRowSaysSoAndThereIsNoWindow() {
        screen(model(), modelInUse = null)

        compose.onNodeWithText("No chat open").performScrollTo().assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithTag("settings.overview.Context window").fetchSemanticsNodes().size,
            "no chat open, so no window to give",
        )
    }

    /**
     * T43: the switches gate the notices they name, so a tap has to write the key the poster reads
     * — not only move a switch on screen.
     */
    /**
     * T44: `/api/settings/usage` was fetched and parsed into nothing. It is now the card the design
     * puts first: the headline, the gateway's own counts, and a bar per day.
     */
    @Test
    fun theUsageCardDrawsTheHeadlineTheCountsAndTheStrip() {
        screen(model())

        compose.onNodeWithTag("settings.usage.headline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("505K").assertIsDisplayed()
        compose.onNodeWithText("140 requests · 89% cache hit rate").assertIsDisplayed()
        compose.onNodeWithTag("settings.usage.strip").assertIsDisplayed()
    }

    @Test
    fun aWindowWithNoUsageSaysSoInsteadOfDrawingAFlatLine() {
        screen(model(), snapshot = SettingsSnapshot(usage = usage(hasUsage = false)))

        compose.onNodeWithTag("settings.usage.empty").performScrollTo().assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithTag("settings.usage.strip").fetchSemanticsNodes().size,
            "no usage is no chart, not a row of zero bars",
        )
    }

    @Test
    fun theNotificationsCopyIsHonestAndTheSwitchesWriteTheKeyThePosterReads() {
        val store = RecordingPreferences()
        val model = SettingsViewModel(Credentials(), Notifier(), store, disconnectGateway = {})
        screen(model)

        compose.onNodeWithTag("settings.notificationsCopy").assertIsDisplayed()
        compose.onNodeWithText("no push service", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("settings.toggle.Needs attention").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(true, model.notificationToggles.last().isOn)
        assertEquals(
            true,
            store.flags[NoticeCategory.NeedsAttention.preferenceKey],
            "the row writes the key the poster reads: ${store.flags}",
        )
    }

    /**
     * T34: the sound is a switch of the reader's own, beside the permission state, and the choice
     * is the key the notifier reads (so the next banner obeys it).
     */
    @Test
    fun theNotificationSoundSitsBesideThePermissionAndIsRemembered() {
        val store = RecordingPreferences()
        val model = SettingsViewModel(Credentials(), Notifier(), store, disconnectGateway = {})
        screen(model)

        compose.onNodeWithTag("settings.permission").performScrollTo().assertIsDisplayed()
        assertEquals(true, model.notificationSound, "a fresh install may play the app's tone")

        compose.onNodeWithTag("settings.notificationSound").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(false, model.notificationSound, "one tap silences the banners")
        assertEquals(
            false,
            store.flags[SettingsViewModel.KEY_NOTIFICATION_SOUND],
            "and the key the notifier reads is the key the row writes",
        )
    }

    /** The preferences as the screen writes them, so a test can see the key the notifier reads. */
    private class RecordingPreferences : SettingsStoring {
        val flags = mutableMapOf<String, Boolean>()
        override fun string(key: String): String? = null
        override fun set(key: String, value: String) = Unit
        override fun flag(key: String): Boolean? = flags[key]
        override fun set(key: String, value: Boolean) {
            flags[key] = value
        }
    }

    @Test
    fun theTestNotificationGoesThroughTheNotifier() {
        val notifier = Notifier()
        screen(model(notifier = notifier))

        compose.onNodeWithTag("settings.testNotification").performScrollTo().performClick()
        // The tap's work is a coroutine on the composition's scope, so the test waits for the
        // notifier to be asked rather than assuming it already was.
        compose.waitUntil(2_000) { notifier.sent.isNotEmpty() }

        assertEquals(listOf("Task completed"), notifier.sent, "a real local notification, which is the only kind there is")
    }

    @Test
    fun appearanceMovesTheThemeAndTheReadingSize() {
        val model = model()
        screen(model)

        compose.onNodeWithTag("settings.theme.Dark").performScrollTo().performClick()
        compose.onNodeWithTag("settings.readingSize.Large").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(SettingsTheme.Dark, model.theme)
        assertEquals(ReadingSize.Large, model.readingSize)
    }

    @Test
    fun disconnectAsksFirstThenForgetsTheGateway() {
        val credentials = Credentials()
        var closed = 0
        val model = SettingsViewModel(credentials, Notifier(), Memory, disconnectGateway = { closed += 1 })
        compose.setContent {
            NanobotTheme {
                SettingsScreen(model = model, loadSnapshot = { SettingsSnapshot() }, onClose = { closed += 1 })
            }
        }

        compose.onNodeWithTag("settings.disconnect").performScrollTo().performClick()
        compose.onNodeWithText("Disconnect gateway?").assertIsDisplayed()
        compose.onNodeWithTag("settings.disconnect.confirm").performClick()
        compose.waitForIdle()

        assertEquals(1, credentials.forgot, "the address and the key go together")
        assertEquals("No gateway", model.gatewaySubtitle)
    }
}
