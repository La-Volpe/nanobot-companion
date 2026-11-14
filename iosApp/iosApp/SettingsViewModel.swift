import Foundation
import UserNotifications

/// Settings (plan.md I7), as one model: what Overview may say, the notification switches and
/// their single honest promise, the appearance preference, and the way out.
@MainActor
final class SettingsViewModel: ObservableObject {

    /// The gateway facts Overview shows. Deliberately no uptime: the payload has none.
    struct Snapshot: Equatable {
        var version: String?
        var commit: String?
        var os: String?
        var pythonVersion: String?
        var modelName: String?
        var modelPreset: String?
        var provider: String?
        var contextWindowTokens: Int?
        var transcriptionProvider: String?
        var transcriptionModel: String?
        var presets: [String] = []
    }

    struct Row: Identifiable, Equatable {
        let label: String
        let detail: String?
        let value: String?

        var id: String { label }
    }

    struct Toggle: Identifiable, Equatable {
        let title: String
        var isOn: Bool
        var id: String { title }
    }

    enum Theme: String, CaseIterable, Equatable {
        case system
        case light
        case dark

        var title: String {
            switch self {
            case .system: "System"
            case .light: "Light"
            case .dark: "Dark"
            }
        }
    }

    enum ReadingSize: String, CaseIterable, Equatable {
        case small
        case medium
        case large

        var title: String {
            switch self {
            case .small: "S"
            case .medium: "M"
            case .large: "L"
            }
        }
    }

    @Published private(set) var snapshot = Snapshot()
    @Published private(set) var notice: String?
    @Published private(set) var theme: Theme
    @Published private(set) var readingSize: ReadingSize
    @Published private(set) var notificationToggles: [Toggle] = [
        Toggle(title: "Task finished", isOn: true),
        Toggle(title: "Task failed", isOn: true),
        Toggle(title: "Needs attention", isOn: false),
    ]

    private let credentials: CredentialsStoring
    private let notifier: Notifying
    private let defaults: SettingsStoring
    /// Set by the shell: a disconnect closes the core and returns to onboarding.
    var disconnectGateway: () -> Void

    init(
        credentials: CredentialsStoring,
        notifier: Notifying,
        defaults: SettingsStoring,
        disconnect: @escaping () -> Void
    ) {
        self.credentials = credentials
        self.notifier = notifier
        self.defaults = defaults
        self.disconnectGateway = disconnect
        self.theme = Theme(rawValue: defaults.string("settings.theme") ?? "") ?? .system
        self.readingSize = ReadingSize(rawValue: defaults.string("settings.readingSize") ?? "") ?? .medium
    }

    // MARK: - the list

    var sections: [String] { ["Gateway", "Overview", "Notifications", "Appearance"] }

    var gatewaySubtitle: String {
        guard let address = credentials.address, !address.isEmpty else { return "No gateway" }
        return "\(address) · connected"
    }

    var disconnectTitle: String { "Disconnect gateway?" }
    var disconnectMessage: String {
        "The app forgets this address and key. Nothing on the gateway is deleted, and you can reconnect any time."
    }
    var disconnectActions: [String] { ["Cancel", "Disconnect"] }

    /// The one promise the Notifications screen can keep, permanently (design README).
    var notificationsCopy: String {
        "Alerts arrive while the app is open, plus a local notification if a task ends while it is "
            + "foregrounded. The gateway has no push service and none is planned, so a closed app "
            + "stays quiet instead of promising an alert it cannot deliver."
    }

    func update(snapshot: Snapshot) {
        self.snapshot = snapshot
    }

    var overviewRows: [Row] {
        var rows: [Row] = []
        rows.append(
            Row(
                label: "nanobot Gateway",
                detail: snapshot.commit.map { "Commit \($0)" },
                value: snapshot.version
            )
        )
        if let os = snapshot.os {
            rows.append(Row(label: "Server", detail: snapshot.pythonVersion.map { "Python \($0)" }, value: os))
        }
        rows.append(Row(label: "Current model", detail: snapshot.modelPreset.map { "Preset \($0)" }, value: snapshot.modelName))
        if let window = snapshot.contextWindowTokens {
            rows.append(Row(label: "Context window", detail: nil, value: "\(window / 1000)k tokens"))
        }
        rows.append(
            Row(
                label: "Transcription",
                detail: snapshot.transcriptionProvider,
                value: snapshot.transcriptionModel?.capitalizedFirst
            )
        )
        rows.append(Row(label: "Model presets", detail: nil, value: "\(snapshot.presets.count)"))
        // No uptime row: the gateway's settings payload has no uptime field, and the design says
        // so rather than guessing one from a process start time this client cannot see.
        rows.append(Row(label: "Address", detail: nil, value: credentials.address ?? "—"))
        return rows
    }

    // MARK: - notifications

    func setToggle(_ id: String, on: Bool) {
        guard let index = notificationToggles.firstIndex(where: { $0.id == id }) else { return }
        notificationToggles[index].isOn = on
    }

    /// The screen's proof that it can keep its promise: a real local notification.
    func sendTestNotification() async {
        notice = nil
        guard await notifier.requestAuthorisation() else {
            notice = "Notifications are turned off for this app in Settings."
            return
        }
        await notifier.send(title: "Task completed", body: "This is a test notification. Tap it to open the chat.")
    }

    // MARK: - appearance

    func setTheme(_ theme: Theme) {
        self.theme = theme
        defaults.set(theme.rawValue, for: "settings.theme")
    }

    func setReadingSize(_ size: ReadingSize) {
        readingSize = size
        defaults.set(size.rawValue, for: "settings.readingSize")
    }

    // MARK: - gateway

    func disconnect() async {
        credentials.forget()
        disconnectGateway()
        notice = "Disconnected. Sign in to reconnect."
    }
}

/// The reading scale: prose, code and the composer, never navigation (design README).
enum ReadingScale {
    static func size(for size: SettingsViewModel.ReadingSize) -> CGFloat {
        switch size {
        case .small: 15
        case .medium: 16
        case .large: 18
        }
    }
}

/// Where preferences live. `UserDefaults` in the app, a dictionary in a test.
protocol SettingsStoring: AnyObject {
    func string(_ key: String) -> String?
    func set(_ value: String, for key: String)
}

extension UserDefaults: SettingsStoring {
    func string(_ key: String) -> String? { string(forKey: key) }
    func set(_ value: String, for key: String) { set(value, forKey: key) }
}

/// The notifier, as a seam: `UNUserNotificationCenter` in the app, a recorder in a test.
@MainActor
protocol Notifying: AnyObject {
    func requestAuthorisation() async -> Bool
    func send(title: String, body: String) async
}

/// A local notification, which is the only kind the gateway can produce (plan.md D8).
@MainActor
final class LocalNotifier: Notifying {
    func requestAuthorisation() async -> Bool {
        let centre = UNUserNotificationCenter.current()
        return (try? await centre.requestAuthorization(options: [.alert, .sound])) ?? false
    }

    func send(title: String, body: String) async {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}

private extension String {
    var capitalizedFirst: String {
        guard let first else { return self }
        return first.uppercased() + dropFirst()
    }
}
