import Foundation
import Testing
@testable import iosApp

/// Settings (plan.md I7): Overview says what the gateway is and never invents an uptime line,
/// Notifications is honest about there being no push transport and proves itself with a real
/// local notification, Appearance owns the theme and the reading size, and Gateway is the way
/// out — a disconnect that forgets the address and the key.
@MainActor
struct SettingsViewModelTests {

    final class FakeNotifier: Notifying {
        var authorisations = 0
        var sent: [String] = []
        var granted = true

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
        func string(_ key: String) -> String? { values[key] }
        func set(_ value: String, for key: String) { values[key] = value }
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
            modelName: "deepseek-v4.1-flash:cloud",
            modelPreset: "primary",
            provider: "ollama",
            contextWindowTokens: 32_768,
            transcriptionProvider: "groq",
            transcriptionModel: "whisper-large-v3",
            presets: ["default", "primary"]
        )
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
        #expect(rows.contains { $0.label == "Context window" && $0.value == "32k tokens" })
        #expect(rows.contains { $0.label == "Transcription" && $0.value == "Whisper-large-v3" })
    }

    /// The plan's red line: notifications are local, and the model never pretends otherwise.
    @Test func testNotificationIsLocalOnly() async {
        let notifier = FakeNotifier()
        let model = model(notifier: notifier)

        await model.sendTestNotification()

        #expect(notifier.authorisations == 1, "the permission is asked for once, on the tap")
        #expect(
            notifier.sent == ["Task completed · This is a test notification. Tap it to open the chat."],
            "a real local notification, which is the only kind the gateway can deliver"
        )
        #expect(model.notificationsCopy.contains("while the app is open"), "the copy is honest, not aspirational")
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

    /// The plan's red line: a disconnect forgets both the address and the key.
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

    @Test func theSectionsAreTheDesignsFour() {
        let model = model()
        model.update(snapshot: snapshot())

        #expect(model.sections == ["Gateway", "Overview", "Notifications", "Appearance"])
        #expect(model.notificationToggles.map(\.title) == ["Task finished", "Task failed", "Needs attention"])
        #expect(model.notificationToggles.first?.isOn == true, "finished tasks are on by default, as drawn")
        #expect(model.notificationToggles.last?.isOn == false, "needs-attention starts off")
    }
}
