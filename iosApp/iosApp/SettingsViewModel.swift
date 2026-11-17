import Foundation
import UserNotifications

/// Settings, as one model: what Overview may say, the notification switches and
/// their single honest promise, the appearance preference, and the way out.
@MainActor
final class SettingsViewModel: ObservableObject {

    /// The gateway facts Overview shows. Deliberately no uptime: the payload has none, and
    /// deliberately no model either: `agent.model` is what the gateway is configured for, so
    /// Overview names the model in use instead: the open chat's composer (`modelInUse`).
    struct Snapshot: Equatable {
        var version: String?
        var commit: String?
        var os: String?
        var pythonVersion: String?
        var transcriptionProvider: String?
        var transcriptionModel: String?
        var presets: [String] = []
        /// The `/api/settings/usage` the client already fetched, drawn as the card's own numbers.
        /// Nil is "the read did not answer", which the section already says.
        var usage: Usage? = nil
    }

    /// Overview's token card: the headline, the gateway's own 30-day counts, and the daily
    /// strip. The window, the gap-filling and the compact copy are the core's (`UsageWindow`);
    /// this is the Swift shape the card draws.
    struct Usage: Equatable {
        /// `505K`, `1.5M`: `formatCompactTokenCount`, the WebUI's own headline.
        var headline: String
        var totalTokens: Int64
        var requests: Int
        /// `62` for 62%; nil when the gateway had no denominator, which is not "0%".
        var cacheHitPercent: Int?
        /// Oldest first, always 30: a day the gateway did not report is a zero bar.
        var bars: [Bar]
        var hasUsage: Bool

        struct Bar: Equatable, Identifiable {
            var date: String
            /// 0…1 of the window's peak, which the core computed so both phones draw one chart.
            var height: Double

            var id: String { date }
        }
    }

    /// The model the open chat is running, as the composer row knows it: the chip's name and
    /// preset and the ring's denominator. The core resolves and words it (`ModelInUse`); this is
    /// the Swift value the row draws. Nil, not empty, is "no chat open".
    struct CurrentModel: Equatable {
        var name: String?
        var preset: String?
        /// The row's second line, already worded by the core: `Preset primary`.
        var presetLabel: String?
        /// `1M`, `200K`, `32.8K`: the core's token copy, without the unit the row adds.
        var windowLabel: String?
    }

    struct Row: Identifiable, Equatable {
        let label: String
        let detail: String?
        let value: String?

        var id: String { label }
    }

    /// One switch: the store key it writes and the poster reads, the words on the row, and the
    /// reader's answer. The key is the core's own (`NoticeCategory.preferenceKey`), one flag, so
    /// the row and the poster cannot disagree about what was turned off.
    struct Toggle: Identifiable, Equatable {
        let key: String
        let title: String
        var isOn: Bool

        var id: String { key }
    }

    /// The three switches the design draws, in its order. The key is the core's, its default is the
    /// design's, and the words are the screen's: a switch that names less than it gates is a switch
    /// that lies, which is why the middle row says "or stopped".
    enum NoticeSwitch: String, CaseIterable, Equatable {
        case finished
        case failedOrStopped
        case needsAttention

        var key: String { "settings.notify.\(rawValue)" }

        var title: String {
            switch self {
            case .finished: "Task finished"
            case .failedOrStopped: "Task failed or stopped"
            case .needsAttention: "Needs attention"
            }
        }

        /// The design's default: finished and failed on, needs-attention off.
        var onByDefault: Bool { self != .needsAttention }
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

    /// Whether Overview's read has answered. Before it did, the section drew rows whose every
    /// value was blank, which reads like the gateway answering "nothing" to each one.
    enum OverviewState: Equatable { case loading, loaded, failed }

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
    /// The open chat's model, the only model Overview may name. Nil is "no chat open": the
    /// section says so rather than printing the gateway's configured default.
    @Published private(set) var modelInUse: CurrentModel?
    /// Whether Overview's read has answered: a skeleton, the rows, or the failure sentence.
    @Published private(set) var overview: OverviewState = .loading
    @Published private(set) var notice: String?
    @Published private(set) var theme: Theme
    @Published private(set) var readingSize: ReadingSize

    /// The three switches, read from the store at launch: a switch that forgets its answer
    /// gates nothing after the next app start.
    @Published private(set) var notificationToggles: [Toggle]

    /// Whether a banner may make a sound. The reader's choice, read at post time by the
    /// same object that draws Settings, so a change applies to the next banner and not the next launch.
    @Published private(set) var notificationSound: Bool

    private let credentials: CredentialsStoring
    private let notifier: Notifying
    private let defaults: SettingsStoring
    /// Set by the shell: a disconnect closes the core and returns to onboarding.
    var disconnectGateway: () -> Void

    init(
        credentials: CredentialsStoring,
        notifier: Notifying,
        defaults: SettingsStoring,
        modelInUse: CurrentModel? = nil,
        disconnect: @escaping () -> Void
    ) {
        self.credentials = credentials
        self.notifier = notifier
        self.defaults = defaults
        self.modelInUse = modelInUse
        self.disconnectGateway = disconnect
        self.theme = Theme(rawValue: defaults.string("settings.theme") ?? "") ?? .system
        self.readingSize = ReadingSize(rawValue: defaults.string("settings.readingSize") ?? "") ?? .medium
        self.notificationSound = defaults.flag(SettingsViewModel.notificationSoundKey) ?? true
        self.notificationToggles = NoticeSwitch.allCases.map { notice in
            Toggle(
                key: notice.key,
                title: notice.title,
                // A switch nobody touched keeps the design's default, which the poster falls back
                // to on the same key.
                isOn: defaults.flag(notice.key) ?? notice.onByDefault
            )
        }
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

    /// The one promise the Notifications screen can keep, permanently (design README), and now
    /// conditional, because the switches above it really do silence what they name.
    var notificationsCopy: String {
        "Alerts arrive while you are away: a local notification when a turn finishes, fails or "
            + "stops, and when nanobot needs an answer, which the switches above choose. The chat "
            + "you are looking at is never announced. The gateway has no push service and none is "
            + "planned, so a closed app stays quiet instead of promising an alert it cannot deliver."
    }

    /// What the phone says about this app's notifications, and the one action that can change it.
    @Published private(set) var permission: AlertsPermission = .notAsked

    var permissionTitle: String {
        switch permission {
        case .granted: "Alerts allowed"
        case .denied: "Alerts turned off"
        case .notAsked: "Alerts not asked yet"
        }
    }

    /// Nil when there is nothing to offer: an allowed permission is not a button.
    var permissionAction: String? {
        switch permission {
        case .granted: nil
        case .denied: "Open system settings"
        case .notAsked: "Allow alerts"
        }
    }

    func refreshPermission() async {
        permission = await notifier.permission()
    }

    /// The row's tap: the system dialog while it can still be drawn, the system screen after "no".
    func applyPermissionChoice() async {
        if permission == .notAsked {
            _ = await notifier.requestAuthorisation()
        } else if permission == .denied {
            notifier.openSystemSettings()
        }
        await refreshPermission()
    }

    func update(snapshot: Snapshot) {
        self.snapshot = snapshot
    }

    /// The shell hands over the open chat's model when the sheet opens and whenever it changes, so
    /// Overview follows a `/model` switch made behind it.
    func update(modelInUse: CurrentModel?) {
        self.modelInUse = modelInUse
    }

    /// The Overview read. Nil is "did not answer", which the section draws as a sentence rather
    /// than as rows with blanks where the gateway's answers belong.
    func refresh(from load: () async -> Snapshot?) async {
        overview = .loading
        guard let loaded = await load() else {
            overview = .failed
            return
        }
        snapshot = loaded
        overview = .loaded
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
        // Deliberately unlike the WebUI (`overview/OverviewSettings.tsx:78` prints
        // `settings.agent.model`): the model row describes the chat on screen, from the same
        // composer the chip and the ring read. A gateway's configured model is not the model in
        // use, and with no chat open there is no model in use to name.
        rows.append(
            Row(
                label: "Current model",
                detail: modelInUse?.presetLabel,
                value: modelInUse?.name ?? "No chat open"
            )
        )
        if let label = modelInUse?.windowLabel {
            rows.append(Row(label: "Context window", detail: nil, value: "\(label) tokens"))
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

    /// The sound is a preference, not a per-banner decision: it lives where a relaunch finds it.
    func setNotificationSound(_ on: Bool) {
        notificationSound = on
        defaults.set(on, for: SettingsViewModel.notificationSoundKey)
    }

    /// An answer the poster honours on the next notice: written to the store, not only to the row.
    func setToggle(_ id: String, on: Bool) {
        guard let index = notificationToggles.firstIndex(where: { $0.id == id }) else { return }
        notificationToggles[index].isOn = on
        defaults.set(on, for: notificationToggles[index].key)
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

    /// The banners and the row above read the same key, so neither can drift from the other.
    static let notificationSoundKey = "settings.notificationSound"
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

    /// An on/off preference; nil when nobody has chosen yet, which is not the same as "off".
    func flag(_ key: String) -> Bool?
    func set(_ value: Bool, for key: String)
}

extension UserDefaults: SettingsStoring {
    func string(_ key: String) -> String? { string(forKey: key) }
    func set(_ value: String, for key: String) { set(value, forKey: key) }
    func flag(_ key: String) -> Bool? { object(forKey: key) as? Bool }
    func set(_ value: Bool, for key: String) { set(value, forKey: key) }
}

/// The notifier, as a seam: `SystemNotificationCentre` in the app (it is the same object the
/// banners go through), a recorder in a test.
@MainActor
protocol Notifying: AnyObject {
    func requestAuthorisation() async -> Bool
    func send(title: String, body: String) async

    /// The system permission as the row names it; *when* to ask is the core's `NotificationPrompt`.
    func permission() async -> AlertsPermission

    /// Where a user turns alerts back on after "no": the phone's own screen, not a second dialog.
    func openSystemSettings()
}

private extension String {
    var capitalizedFirst: String {
        guard let first else { return self }
        return first.uppercased() + dropFirst()
    }
}
