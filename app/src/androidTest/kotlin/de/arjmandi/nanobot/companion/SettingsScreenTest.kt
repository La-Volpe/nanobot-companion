package de.arjmandi.nanobot.companion

import de.arjmandi.nanobot.companion.projection.NotificationPermission

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.settings.Notifying
import de.arjmandi.nanobot.companion.settings.ReadingSize
import de.arjmandi.nanobot.companion.settings.SettingsCredentials
import de.arjmandi.nanobot.companion.settings.SettingsScreen
import de.arjmandi.nanobot.companion.settings.SettingsSnapshot
import de.arjmandi.nanobot.companion.settings.SettingsStoring
import de.arjmandi.nanobot.companion.settings.SettingsTheme
import de.arjmandi.nanobot.companion.settings.SettingsViewModel
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
    }

    private fun model(credentials: Credentials = Credentials(), notifier: Notifier = Notifier()) =
        SettingsViewModel(credentials, notifier, Memory, disconnectGateway = {})

    private fun screen(
        model: SettingsViewModel,
        snapshot: SettingsSnapshot = SettingsSnapshot(
            version = "0.4.1",
            commit = "28d73b69",
            os = "Darwin",
            pythonVersion = "3.12.9",
            modelName = "deepseek-v4.1-flash:cloud",
            modelPreset = "primary",
            contextWindowTokens = 32_768,
            presets = listOf("default", "primary"),
        ),
    ) {
        compose.setContent {
            NanobotTheme { SettingsScreen(model = model, loadSnapshot = { snapshot }, onClose = {}) }
        }
    }

    @Test
    fun overviewShowsTheVersionTheCommitAndNoUptime() {
        screen(model())

        compose.onNodeWithTag("settings.overview.nanobot Gateway").assertIsDisplayed()
        compose.onNodeWithText("Commit 28d73b69").assertIsDisplayed()
        compose.onNodeWithText("32k tokens").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Uptime", substring = true, ignoreCase = true).fetchSemanticsNodes().size,
            "the gateway's payload has no uptime field",
        )
    }

    @Test
    fun theNotificationsCopyIsHonestAndTheSwitchesMove() {
        val model = model()
        screen(model)

        compose.onNodeWithTag("settings.notificationsCopy").assertIsDisplayed()
        compose.onNodeWithText("no push service", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("settings.toggle.Needs attention").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(true, model.notificationToggles.last().isOn)
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
