package de.arjmandi.nanobot.companion.settings

import de.arjmandi.nanobot.companion.projection.NotificationPermission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Settings (plan.md A7, the Android twin of iOS's `SettingsViewModelTests`): Overview says what the
 * gateway is and never invents an uptime line, Notifications is honest about there being no push
 * transport and proves itself with a real local notification, Appearance owns the theme and the
 * reading size, and Gateway is the way out — a disconnect that forgets the address and the key.
 */
class SettingsViewModelTest {

    private class FakeCredentials(
        override var address: String? = "127.0.0.1:18765",
    ) : SettingsCredentials {
        var forgot = 0

        override fun forget() {
            forgot += 1
            address = null
        }
    }

    private class FakeNotifier(
        var granted: Boolean = true,
        var state: NotificationPermission = NotificationPermission.Granted,
    ) : Notifying {
        var authorisations = 0
        var asked = 0
        var systemSettings = 0
        val sent = mutableListOf<String>()

        override suspend fun requestAuthorisation(): Boolean {
            authorisations += 1
            return granted
        }

        override suspend fun send(title: String, body: String) {
            sent += "$title · $body"
        }

        override fun permission(): NotificationPermission = state

        override fun openSystemSettings() {
            systemSettings += 1
        }

        override fun recordAsked(granted: Boolean) {
            asked += 1
            state = if (granted) NotificationPermission.Granted else NotificationPermission.Denied
        }
    }

    private class FakePreferences : SettingsStoring {
        val values = mutableMapOf<String, String>()
        val flags = mutableMapOf<String, Boolean>()
        override fun string(key: String): String? = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }

        override fun flag(key: String): Boolean? = flags[key]
        override fun set(key: String, value: Boolean) {
            flags[key] = value
        }
    }

    private fun model(
        credentials: FakeCredentials = FakeCredentials(),
        notifier: FakeNotifier = FakeNotifier(),
        preferences: FakePreferences = FakePreferences(),
        disconnect: () -> Unit = {},
    ) = SettingsViewModel(credentials, notifier, preferences, disconnect)

    private fun snapshot() = SettingsSnapshot(
        version = "0.4.1",
        commit = "28d73b69",
        os = "Darwin",
        pythonVersion = "3.12.9",
        modelName = "deepseek-v4.1-flash:cloud",
        modelPreset = "primary",
        provider = "ollama",
        contextWindowTokens = 32_768,
        contextWindowLabel = "32.8K",
        transcriptionProvider = "groq",
        transcriptionModel = "whisper-large-v3",
        presets = listOf("default", "primary"),
    )

    @Test
    fun overviewSaysWhetherItIsStillReadingAndEndsInASentenceWhenItCannot() = runTest {
        val model = model()
        assertEquals(OverviewState.Loading, model.overview, "the section opens before the read answers")

        model.refresh { snapshot() }
        assertEquals(OverviewState.Loaded, model.overview)
        assertEquals("0.4.1", model.overviewRows.first().value)

        // A refused read is the app's problem, and Overview must not draw blanks as if they were
        // the gateway's answers.
        val refused = model()
        refused.refresh { null }
        assertEquals(OverviewState.Failed, refused.overview)
    }

    /** The design's one hard rule about Overview: no uptime field, because the payload has none. */
    @Test
    fun overviewHasAVersionAndACommitAndNoUptime() {
        val model = model()
        model.update(snapshot())

        val rows = model.overviewRows
        val labels = rows.map { it.label }
        assertTrue(labels.contains("nanobot Gateway"))
        assertEquals("0.4.1", rows.first { it.label == "nanobot Gateway" }.value)
        assertEquals("Commit 28d73b69", rows.first { it.label == "nanobot Gateway" }.detail)
        assertTrue(labels.none { it.lowercase().contains("uptime") }, "the payload has no uptime: $labels")
        assertTrue(rows.contains(SettingsRow("Context window", detail = null, value = "32.8K tokens")))
        assertEquals("Whisper-large-v3", rows.first { it.label == "Transcription" }.value)
    }

    /**
     * T36: the owner's gateway answers 1 000 000 and the row read "1000k tokens". The WebUI
     * prints a million as `1M` (`model-context-format.ts`), so this row says `1M tokens` — the
     * unit stays, because a bare `1M` next to a version string reads like one.
     */
    @Test
    fun aMillionTokenWindowReadsOneM() {
        val model = model()
        // The label is the core's (`TokenCountTest` pins 1 000 000 -> 1M); the row adds the unit.
        model.update(snapshot().copy(contextWindowTokens = 1_000_000, contextWindowLabel = "1M"))

        assertTrue(
            model.overviewRows.contains(SettingsRow("Context window", detail = null, value = "1M tokens")),
            "a million is 1M, not 1000k: ${model.overviewRows}",
        )
    }

    /** The plan's red line: notifications are local, and the model never pretends otherwise. */
    @Test
    fun testNotificationIsLocalOnly() = runTest {
        val notifier = FakeNotifier()
        val model = model(notifier = notifier)

        model.sendTestNotification()

        assertEquals(1, notifier.authorisations, "the permission is asked for once, on the tap")
        assertEquals(
            listOf("Task completed · This is a test notification. Tap it to open the chat."),
            notifier.sent,
            "a real local notification, which is the only kind the gateway can deliver",
        )
        assertTrue(model.notificationsCopy.contains("while you are away"), "the copy is honest, not aspirational")
        assertTrue(model.notificationsCopy.contains("no push service"), "and says why: the gateway has none")
    }

    /** The row names the system's answer, and offers the one thing that can change it. */
    @Test
    fun theRowsStateFollowsTheSystemPermission() = runTest {
        val notifier = FakeNotifier(state = NotificationPermission.NotAsked)
        val model = model(notifier = notifier)

        model.refreshPermission()
        assertEquals(NotificationPermission.NotAsked, model.permission)

        model.answeredPermission(granted = false)

        assertEquals(NotificationPermission.Denied, model.permission, "the answer stands")
        assertEquals(1, notifier.asked, "a shown dialog is never shown again")

        model.openNotificationSettings()
        assertEquals(1, notifier.systemSettings, "a refusal's only honest offer is the system screen")
    }

    @Test
    fun aDeniedPermissionSaysSoInsteadOfPretending() = runTest {
        val notifier = FakeNotifier(granted = false)
        val model = model(notifier = notifier)

        model.sendTestNotification()

        assertTrue(notifier.sent.isEmpty(), "nothing is sent when the system refuses")
        assertEquals(SettingsNotice.NotificationsOff, model.notice)
    }

    /** The plan's red line: a disconnect forgets both the address and the key. */
    @Test
    fun disconnectClearsKeystore() = runTest {
        val credentials = FakeCredentials()
        var disconnected = 0
        val model = model(credentials = credentials, disconnect = { disconnected += 1 })
        model.update(snapshot())

        assertEquals("127.0.0.1:18765 · connected", model.gatewaySubtitle)

        model.disconnect()

        assertEquals(1, credentials.forgot, "the credentials store is asked to forget")
        assertNull(credentials.address, "the address goes with the key")
        assertEquals(1, disconnected, "and the shell closes the socket and returns to onboarding")
        assertEquals("No gateway", model.gatewaySubtitle, "the row says so")
        assertEquals(SettingsNotice.Disconnected, model.notice)
    }

    @Test
    fun theDisconnectQuestionSaysWhatItDoesNotDo() {
        val model = model()

        assertEquals("Disconnect gateway?", model.disconnectTitle)
        assertEquals(
            "The app forgets this address and key. Nothing on the gateway is deleted, and you can reconnect any time.",
            model.disconnectMessage,
        )
        assertEquals(listOf("Cancel", "Disconnect"), model.disconnectActions)
    }

    @Test
    fun appearanceIsThemeAndReadingSizeOnly() {
        val preferences = FakePreferences()
        val model = model(preferences = preferences)

        assertEquals(SettingsTheme.System, model.theme, "the design's default is System")
        assertEquals(ReadingSize.Medium, model.readingSize)

        model.chooseTheme(SettingsTheme.Dark)
        model.chooseReadingSize(ReadingSize.Large)

        assertEquals(SettingsTheme.Dark, model.theme)
        assertEquals(ReadingSize.Large, model.readingSize)
        assertEquals("dark", preferences.values["settings.theme"], "and the choice survives a relaunch")
        assertEquals("large", preferences.values["settings.readingSize"])
        assertEquals(18, ReadingScale.size(ReadingSize.Large), "the reading scale moves prose, code and the composer, nothing else")
        assertEquals(15, ReadingScale.size(ReadingSize.Small))
        assertEquals(16, ReadingScale.size(ReadingSize.Medium))
    }

    @Test
    fun theSectionsAreTheDesignsFour() {
        val model = model()
        model.update(snapshot())

        assertEquals(listOf("Gateway", "Overview", "Notifications", "Appearance"), model.sections)
        assertEquals(listOf("Task finished", "Task failed", "Needs attention"), model.notificationToggles.map { it.title })
        assertTrue(model.notificationToggles.first().isOn, "finished tasks are on by default, as drawn")
        assertFalse(model.notificationToggles.last().isOn, "needs-attention starts off")
    }

    @Test
    fun aToggleIsRememberedAsLongAsTheScreenLives() {
        val model = model()

        model.setToggle("Needs attention", on = true)

        assertTrue(model.notificationToggles.last().isOn)
    }
}
