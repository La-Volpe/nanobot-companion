import Foundation
import Testing
import core
@testable import iosApp

/// Settings: Overview says what the gateway is and never invents an uptime line,
/// Notifications is honest about there being no push transport and proves itself with a real
/// local notification, Appearance owns the theme and the reading size, and Gateway is the way
/// out, which is a disconnect that forgets the address and the key.
@MainActor
struct SettingsViewModelTests {

    final class FakeNotifier: Notifying {
        var authorisations = 0
        var sent: [String] = []
        var granted = true
        var state: AlertsPermission = .granted
        var settingsOpened = 0

        func permission() async -> AlertsPermission { state }

        func openSystemSettings() { settingsOpened += 1 }

        func requestAuthorisation() async -> Bool {
            authorisations += 1
            return granted
        }

        func send(title: String, body: String) async {
            sent.append(title + " · " + body)
        }
    }

    final class FakeDefaults: SettingsStoring {
        var values: [String: String] = [:]
        var flags: [String: Bool] = [:]
        func string(_ key: String) -> String? { values[key] }
        func set(_ value: String, for key: String) { values[key] = value }
        func flag(_ key: String) -> Bool? { flags[key] }
        func set(_ value: Bool, for key: String) { flags[key] = value }
    }

    final class FakeCredentials: CredentialsStoring {
        var address: String? = "127.0.0.1:18765"
        var storedKey: String? = "a-key"
        var allowUnverifiedCertificate = true
        private(set) var forgot = 0
        func remember(address: String, key: String, allowUnverifiedCertificate: Bool) {}
        func setAllowUnverifiedCertificate(_ allowed: Bool) {}
        func forget() {
            forgot += 1
            address = nil
            allowUnverifiedCertificate = false
        }
    }

    private func model(
        credentials: FakeCredentials? = nil,
        notifier: FakeNotifier? = nil,
        defaults: FakeDefaults? = nil,
        disconnect: @escaping () -> Void = {}
    ) -> SettingsViewModel {
        SettingsViewModel(
            credentials: credentials ?? FakeCredentials(),
            notifier: notifier ?? FakeNotifier(),
            defaults: defaults ?? FakeDefaults(),
            disconnect: disconnect
        )
    }

    private func snapshot() -> SettingsViewModel.Snapshot {
        SettingsViewModel.Snapshot(
            version: "0.4.1",
            commit: "28d73b69",
            os: "Darwin",
            pythonVersion: "3.12.9",
            transcriptionProvider: "groq",
            transcriptionModel: "whisper-large-v3",
            presets: ["default", "primary"]
        )
    }

    /// The open chat's model as the shell hands it over: the core words the two lines.
    private func chat(preset: String = "primary", name: String = "glm-5.3-flash:cloud", window: String? = "1M") -> SettingsViewModel.CurrentModel {
        SettingsViewModel.CurrentModel(name: name, preset: preset, presetLabel: "Preset \(preset)", windowLabel: window)
    }

    /// The notification sound is a preference of the reader's, on until they say otherwise,
    /// and the next launch reads the same key the banners do.
    @Test func theNotificationSoundIsOnUntilItIsTurnedOffAndTheChoiceOutlivesTheApp() {
        let defaults = FakeDefaults()
        let first = model(defaults: defaults)
        #expect(first.notificationSound, "a fresh install may play the app's tone")

        first.setNotificationSound(false)

        #expect(first.notificationSound == false)
        #expect(
            defaults.flags[SettingsViewModel.notificationSoundKey] == false,
            "the key the notifier reads is the key the row writes"
        )
        let afterRelaunch = model(defaults: defaults)
        #expect(afterRelaunch.notificationSound == false, "the choice survives the app that made it")
    }

    @Test func overviewSaysWhetherItIsStillReadingAndEndsInASentenceWhenItCannot() async {
        let reading = model()
        #expect(reading.overview == .loading, "the section opens before the read answers")

        let loaded = snapshot()
        await reading.refresh { loaded }
        #expect(reading.overview == .loaded)
        #expect(reading.overviewRows.first?.value == "0.4.1")

        // A refused read is the app's problem, and Overview must not draw blanks as if they were
        // the gateway's answers.
        let refused = model()
        await refused.refresh { nil }
        #expect(refused.overview == .failed)
    }

    /// The design's one hard rule about Overview: no uptime field, because the payload has none.
    @Test func overviewHasAVersionAndACommitAndNoUptime() {
        let model = model()
        model.update(snapshot: snapshot())

        let rows = model.overviewRows
        let labels = rows.map(\.label)
        #expect(labels.contains("nanobot Gateway"))
        #expect(rows.first { $0.label == "nanobot Gateway" }?.value == "0.4.1")
        #expect(rows.first { $0.label == "nanobot Gateway" }?.detail == "Commit 28d73b69")
        #expect(labels.allSatisfy { !$0.lowercased().contains("uptime") }, "the payload has no uptime: \(labels)")
        #expect(rows.contains { $0.label == "Transcription" && $0.value == "Whisper-large-v3" })
    }

    /// The window row is the *chat's* window, and a million is `1M`, and the
    /// unit stays, because a bare `1M` next to a version string reads like one. The label itself
    /// is the core's (`ModelInUseTest` pins 1 000 000 -> 1M).
    @Test func theWindowRowIsTheChatsAndReadsOneM() {
        let model = model()
        model.update(modelInUse: chat())

        #expect(
            model.overviewRows.contains { $0.label == "Current model" && $0.value == "glm-5.3-flash:cloud" && $0.detail == "Preset primary" },
            "the chat's model and preset: \(model.overviewRows)"
        )
        #expect(
            model.overviewRows.contains { $0.label == "Context window" && $0.value == "1M tokens" },
            "a million is 1M, not 1000k: \(model.overviewRows)"
        )
    }

    /// A chat whose window nobody reported has no denominator: the row is absent rather than zero.
    @Test func noWindowIsNoRow() {
        let model = model()
        model.update(modelInUse: chat(window: nil))

        #expect(model.overviewRows.contains { $0.label == "Current model" })
        #expect(!model.overviewRows.contains { $0.label == "Context window" }, "\(model.overviewRows)")
    }

    /// Notifications are local, and the model never pretends otherwise.
    @Test func testNotificationIsLocalOnly() async {
        let notifier = FakeNotifier()
        let model = model(notifier: notifier)

        await model.sendTestNotification()

        #expect(notifier.authorisations == 1, "the permission is asked for once, on the tap")
        #expect(
            notifier.sent == ["Task completed · This is a test notification. Tap it to open the chat."],
            "a real local notification, which is the only kind the gateway can deliver"
        )
        #expect(model.notificationsCopy.contains("while you are away"), "the copy is honest, not aspirational")
        #expect(model.notificationsCopy.contains("no push service"), "and says why: the gateway has none")
    }

    @Test func aDeniedPermissionSaysSoInsteadOfPretending() async {
        let notifier = FakeNotifier()
        notifier.granted = false
        let model = model(notifier: notifier)

        await model.sendTestNotification()

        #expect(notifier.sent.isEmpty, "nothing is sent when the system refuses")
        #expect(model.notice == "Notifications are turned off for this app in Settings.")
    }

    /// A disconnect forgets both the address and the key.
    @Test func disconnectForgetsSecureStore() async {
        let credentials = FakeCredentials()
        let notifier = FakeNotifier()
        let model = model(credentials: credentials, notifier: notifier)
        model.update(snapshot: snapshot())

        #expect(model.gatewaySubtitle == "127.0.0.1:18765 · connected")

        await model.disconnect()

        #expect(credentials.forgot == 1, "the credentials seam is asked to forget")
        #expect(credentials.address == nil, "the address goes with the key")
        #expect(model.gatewaySubtitle == "No gateway", "and the row says so")
        #expect(model.notice == "Disconnected. Sign in to reconnect.")
    }

    @Test func theDisconnectQuestionSaysWhatItDoesNotDo() {
        let model = model()

        #expect(model.disconnectTitle == "Disconnect gateway?")
        #expect(model.disconnectMessage == "The app forgets this address and key. Nothing on the gateway is deleted, and you can reconnect any time.")
        #expect(model.disconnectActions == ["Cancel", "Disconnect"])
    }

    @Test func appearanceIsThemeAndReadingSizeOnly() {
        let defaults = FakeDefaults()
        let model = model(defaults: defaults)

        #expect(model.theme == .system, "the design's default is System")
        #expect(model.readingSize == .medium)

        model.setTheme(.dark)
        model.setReadingSize(.large)

        #expect(model.theme == .dark)
        #expect(model.readingSize == .large)
        #expect(defaults.values["settings.theme"] == "dark", "and the choice survives a relaunch")
        #expect(defaults.values["settings.readingSize"] == "large")
        #expect(ReadingScale.size(for: .large) == 18, "the reading scale moves prose, code and the composer, nothing else")
        #expect(ReadingScale.size(for: .small) == 15)
        #expect(ReadingScale.size(for: .medium) == 16)
    }

    /// The owner's rule: every name and size on screen describes the model *in use*, never
    /// the gateway's configured default. `/api/settings` `agent.model` is a configuration (the
    /// WebUI's own Overview row prints it, `OverviewSettings.tsx:78`); with no chat open there is
    /// no model in use, and the row says so instead of naming one that is not answering.
    @Test func overviewSaysThereIsNoChatOpenInsteadOfNamingTheGatewaysModel() {
        let model = model()
        model.update(snapshot: snapshot())

        let current = model.overviewRows.first { $0.label == "Current model" }
        #expect(
            current?.value == "No chat open",
            "`agent.model` is what the gateway is configured for, not what answers here: \(String(describing: current))"
        )
        #expect(
            !model.overviewRows.contains { $0.label == "Context window" },
            "with no chat open there is no window to give: \(model.overviewRows)"
        )
    }

    /// The switches used to be local state that persisted nowhere and gated nothing. Each one
    /// is stored under the core's own key (`NoticeCategory.preferenceKey`), which is the key the
    /// poster reads at post time.
    @Test func everySwitchIsStoredUnderTheKeyThePosterReads() {
        let defaults = FakeDefaults()
        let model = model(defaults: defaults)
        let needsAttention = model.notificationToggles[2]

        model.setToggle(needsAttention.id, on: true)

        #expect(model.notificationToggles[2].isOn)
        #expect(
            defaults.flags[needsAttention.id] == true,
            "the row writes the key the poster reads: \(defaults.flags)"
        )
    }

    @Test func theSwitchesOutliveTheApp() {
        let defaults = FakeDefaults()
        let first = model(defaults: defaults)
        first.setToggle(first.notificationToggles[0].id, on: false)

        let afterRelaunch = model(defaults: defaults)

        #expect(
            afterRelaunch.notificationToggles[0].isOn == false,
            "a choice that does not survive a launch is not a preference: \(afterRelaunch.notificationToggles)"
        )
        #expect(afterRelaunch.notificationToggles[2].isOn == false, "and an untouched switch keeps the design's default")
    }

    /// The switch the screen draws and the switch the poster checks are one list. The rows'
    /// keys are the core's own strings, so a rename on either side is a test, not a silent switch.
    @Test func everyDrawnSwitchNamesACoreCategory() {
        let drawn = model().notificationToggles.map(\.id)

        #expect(
            drawn == core.NoticeCategory.entries.map(\.preferenceKey),
            "the Notifications rows must name the categories the poster checks: \(drawn)"
        )
    }

    @Test func theSectionsAreTheDesignsFour() {
        let model = model()
        model.update(snapshot: snapshot())

        #expect(model.sections == ["Gateway", "Overview", "Notifications", "Appearance"])
        #expect(
            model.notificationToggles.map(\.title) == ["Task finished", "Task failed or stopped", "Needs attention"],
            "the middle row names what it gates: a stop is not a failure to announce"
        )
        #expect(model.notificationToggles.first?.isOn == true, "finished tasks are on by default, as drawn")
        #expect(model.notificationToggles.last?.isOn == false, "needs-attention starts off")
    }
}
