package de.arjmandi.nanobot.companion.settings

import de.arjmandi.nanobot.companion.projection.NoticeCategory
import de.arjmandi.nanobot.companion.projection.NotificationPermission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Settings (the Android twin of iOS's `SettingsViewModelTests`): Overview says what the
 * gateway is and never invents an uptime line, Notifications is honest about there being no push
 * transport and proves itself with a real local notification, Appearance owns the theme and the
 * reading size, and Gateway is the way out: a disconnect that forgets the address and the key.
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
        transcriptionProvider = "groq",
        transcriptionModel = "whisper-large-v3",
        presets = listOf("default", "primary"),
    )

    /** The open chat's model as the shell hands it over: the core words the two lines. */
    private fun chat(
        preset: String = "primary",
        name: String = "glm-5.3-flash:cloud",
        window: String? = "1M",
    ) = ChatModelInUse(name = name, preset = preset, presetLabel = "Preset $preset", windowLabel = window)

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
        assertEquals("Whisper-large-v3", rows.first { it.label == "Transcription" }.value)
    }

    /**
     * The window row is the *chat's* window, and a million is `1M`: the unit
     * stays, because a bare `1M` next to a version string reads like one. The label itself is the
     * core's (`ModelInUseTest` pins 1 000 000 -> 1M).
     */
    @Test
    fun theWindowRowIsTheChatsAndReadsOneM() {
        val model = model()
        model.update(modelInUse = chat())

        assertTrue(
            model.overviewRows.contains(
                SettingsRow("Current model", detail = "Preset primary", value = "glm-5.3-flash:cloud"),
            ),
            "the chat's model and preset, not the gateway's configuration: ${model.overviewRows}",
        )
        assertTrue(
            model.overviewRows.contains(SettingsRow("Context window", detail = null, value = "1M tokens")),
            "a million is 1M, not 1000k: ${model.overviewRows}",
        )
    }

    /** A chat whose window nobody reported has no denominator: the row is absent rather than zero. */
    @Test
    fun noWindowIsNoRow() {
        val model = model()
        model.update(modelInUse = chat(window = null))

        assertTrue(model.overviewRows.any { it.label == "Current model" })
        assertTrue(model.overviewRows.none { it.label == "Context window" }, "${model.overviewRows}")
    }

    /** Notifications are local, and the model never pretends otherwise. */
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

    /** A disconnect forgets both the address and the key. */
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
        assertEquals(
            listOf("Task finished", "Task failed or stopped", "Needs attention"),
            model.notificationToggles.map { it.title },
            "the middle row names what it gates: a stop is not a failure to announce",
        )
        assertTrue(model.notificationToggles.first().isOn, "finished tasks are on by default, as drawn")
        assertFalse(model.notificationToggles.last().isOn, "needs-attention starts off")
    }

    /**
     * The owner's rule: every name and size on screen describes the model in use, never the
     * gateway's configured default. `/api/settings` `agent.model` is a configuration (the WebUI's
     * own Overview row prints it, `OverviewSettings.tsx:78`); with no chat open there is no model
     * in use, and the row says so instead of naming one that is not answering.
     */
    @Test
    fun overviewSaysThereIsNoChatOpenInsteadOfNamingTheGatewaysModel() {
        val model = model()
        model.update(snapshot())

        val current = model.overviewRows.firstOrNull { it.label == "Current model" }
        assertEquals(
            "No chat open",
            current?.value,
            "`agent.model` is what the gateway is configured for, not what answers here",
        )
        assertTrue(
            model.overviewRows.none { it.label == "Context window" },
            "with no chat open there is no window to give: ${model.overviewRows}",
        )
    }

    /**
     * The switches used to be local state that persisted nowhere and gated nothing. Each one is
     * stored under the core's own key (`NoticeCategory.preferenceKey`), which is the key the poster
     * reads at post time.
     */
    @Test
    fun everySwitchIsStoredUnderTheKeyThePosterReads() {
        val preferences = FakePreferences()
        val model = model(preferences = preferences)

        model.setToggle(NoticeCategory.NeedsAttention, on = true)

        assertTrue(model.notificationToggles.last().isOn)
        assertEquals(
            true,
            preferences.flags[NoticeCategory.NeedsAttention.preferenceKey],
            "the row writes the key the poster reads: ${preferences.flags}",
        )
    }

    @Test
    fun theSwitchesOutliveTheApp() {
        val preferences = FakePreferences()
        model(preferences = preferences).setToggle(NoticeCategory.Finished, on = false)

        val afterRelaunch = model(preferences = preferences)

        assertFalse(
            afterRelaunch.notificationToggles.first { it.title == "Task finished" }.isOn,
            "a choice that does not survive a launch is not a preference: ${afterRelaunch.notificationToggles}",
        )
        assertTrue(
            afterRelaunch.notificationToggles.first { it.title == "Needs attention" }.isOn == false,
            "and a switch nobody touched keeps the design's default",
        )
    }
}
