import Foundation
import UIKit
import UserNotifications
import core

/// Kotlin/Native exports `NotificationPermission.NotAsked` as `notasked`; the adapter reads the
/// name the enum has in Kotlin so the rest of the app never has to.
extension NotificationPermission {
    static var notAsked: NotificationPermission { .notasked }
}

/// The permission as the app's own screens name it: the Kotlin framework is the adapter layer's
/// business (`LayeringTests`), so the view models speak this and the adapter translates.
enum AlertsPermission: Equatable {
    case notAsked
    case granted
    case denied
}

extension NotificationPermission {
    var asAlerts: AlertsPermission {
        if self == NotificationPermission.granted { return .granted }
        if self == NotificationPermission.denied { return .denied }
        return .notAsked
    }
}

/// The system work a banner needs, so the rules around it are testable without the Notification
/// Center: a fake posts, refuses, or reports a permission, and the model is what gets checked.
@MainActor
protocol BannerPosting: AnyObject {
    func permission() async -> core.NotificationPermission

    /// True when the banner is on the phone now; false when the system refused to show it.
    func post(notice: core.TaskNotice) async -> Bool

    /// The system dialog. Only ever asked through [TaskNoticeCenter.askIfJustified].
    func request() async -> Bool

    func cancel(chatId: String)
    func openSystemSettings()
}

/**
 * One notice in, at most one banner out. Whether a frame deserved a banner is the core's rule
 * (`TaskNoticeRule`, `projection/TaskNotices.kt`); what lives here is what only iOS knows: a banner
 * the system refused is remembered so the permission is asked at the first moment it is justified
 * (`NotificationPrompt`), and a tap names the chat to open rather than a screen to rebuild.
 */
@MainActor
final class TaskNoticeCenter {

    private let posting: BannerPosting

    /// The last key posted or refused, so a repeated notice is not a second banner.
    private var announced: String?

    /// A banner went missing: the next time the app is in front of the user is when to ask.
    private(set) var missedNotice = false

    init(posting: BannerPosting) {
        self.posting = posting
    }

    /// The core's rule, given the permission this phone reports.
    func shouldAsk(permission: core.NotificationPermission) -> Bool {
        core.NotificationPrompt.shared.shouldAsk(permission: permission, missedANotice: missedNotice)
    }

    func permission() async -> AlertsPermission {
        await posting.permission().asAlerts
    }

    @discardableResult
    func deliver(_ notice: core.TaskNotice) async -> Bool {
        guard notice.key != announced else { return false }
        announced = notice.key
        let posted = await posting.post(notice: notice)
        missedNotice = !posted
        return posted
    }

    /// Ask once, and only when a banner actually went missing. Returns the state afterwards.
    @discardableResult
    func askIfJustified() async -> AlertsPermission {
        let current = await posting.permission()
        guard shouldAsk(permission: current) else { return current.asAlerts }
        if await posting.request() {
            missedNotice = false
            // The banner the answer was for is worth another try, so the key is forgotten.
            announced = nil
        }
        return await posting.permission().asAlerts
    }

    /// The chat was opened by hand, so its banner has nothing left to say.
    func cancel(chatId: String) {
        posting.cancel(chatId: chatId)
    }

    func openSystemSettings() {
        posting.openSystemSettings()
    }
}

/// The words a banner is allowed to carry: the outcome, and nothing from the conversation. The
/// WebUI keeps the same rule for its own notifications (`lib/browser-notifications.ts`), because
/// a lock screen shows what it says to whoever is holding the phone.
enum TaskNoticeCopy {
    static func title(for outcome: core.TaskOutcome) -> String {
        if outcome == core.TaskOutcome.completed { return "Task completed" }
        if outcome == core.TaskOutcome.failed { return "Task failed" }
        if outcome == core.TaskOutcome.interrupted { return "Task stopped" }
        return "nanobot needs you"
    }

    static let body = "Tap to open the chat."
}

/// The real one: `UNUserNotificationCenter` behind [BannerPosting], plus the two delegate calls a
/// local notification needs — a banner while the app is in front, and a tap that names its chat.
@MainActor
final class SystemNotificationCentre: NSObject, BannerPosting {

    private let centre = UNUserNotificationCenter.current()

    /// A tap arrived; the shell is who can open a chat, so it is handed the id.
    var onOpenChat: ((String) -> Void)?

    override init() {
        super.init()
        // Set before the app finishes launching, which is the only moment a cold-launch tap is
        // delivered to a delegate that exists.
        centre.delegate = self
    }

    func permission() async -> core.NotificationPermission {
        let settings = await centre.notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined: return .notAsked
        case .denied: return core.NotificationPermission.denied
        default: return core.NotificationPermission.granted
        }
    }

    func request() async -> Bool {
        (try? await centre.requestAuthorization(options: [.alert, .sound])) ?? false
    }

    func post(notice: core.TaskNotice) async -> Bool {
        // A post without the permission is silently dropped by the system, so this asks first and
        // says so: the model turns a `false` into the one permission question worth asking.
        guard await permission() == core.NotificationPermission.granted else { return false }
        let content = UNMutableNotificationContent()
        content.title = TaskNoticeCopy.title(for: notice.outcome)
        content.body = TaskNoticeCopy.body
        content.sound = .default
        content.userInfo = ["chatId": notice.chatId]
        // One id per chat: a second banner for the same chat replaces the first instead of
        // stacking, and opening the chat can withdraw it by the same id.
        let request = UNNotificationRequest(identifier: notice.chatId, content: content, trigger: nil)
        do {
            try await centre.add(request)
            return true
        } catch {
            return false
        }
    }

    func cancel(chatId: String) {
        centre.removeDeliveredNotifications(withIdentifiers: [chatId])
        centre.removePendingNotificationRequests(withIdentifiers: [chatId])
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

extension SystemNotificationCentre: UNUserNotificationCenterDelegate {

    /// The core already excluded the chat the user is looking at, so a banner in the foreground
    /// is a chat that is *not* on screen: show it instead of swallowing it.
    nonisolated func userNotificationCenter(
        _ centre: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    nonisolated func userNotificationCenter(
        _ centre: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard let chatId = response.notification.request.content.userInfo["chatId"] as? String else { return }
        await MainActor.run { self.onOpenChat?(chatId) }
    }
}

/// Settings asks the same system the banners do, so it is the same object: one implementation of
/// "ask", "post" and "open the phone's screen", not two that could disagree.
extension SystemNotificationCentre: Notifying {
    func requestAuthorisation() async -> Bool {
        await request()
    }

    func permission() async -> AlertsPermission {
        await permission().asAlerts
    }

    func send(title: String, body: String) async {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        try? await centre.add(request)
    }
}
