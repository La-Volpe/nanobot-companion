import Foundation
import Testing
import core
@testable import iosApp

/// The phone half of a task notice: post what the core allowed, remember what the system refused,
/// and ask for the permission once — at the first banner that went missing, never at launch.
@MainActor
struct TaskNoticeCenterTests {

    @MainActor
    final class FakePosting: BannerPosting {
        var state: core.NotificationPermission = core.NotificationPermission.granted
        var accepts = true
        var requests = 0
        var posted: [String] = []
        var cancelled: [String] = []
        var settingsOpened = 0

        func permission() async -> core.NotificationPermission { state }

        func post(notice: core.TaskNotice) async -> Bool {
            guard accepts else { return false }
            posted.append(notice.key)
            return true
        }

        func request() async -> Bool {
            requests += 1
            state = accepts ? core.NotificationPermission.granted : core.NotificationPermission.denied
            return accepts
        }

        func cancel(chatId: String) { cancelled.append(chatId) }
        func openSystemSettings() { settingsOpened += 1 }
    }

    private func notice(_ key: String = "websocket:a|turn:turn-1|Completed") -> core.TaskNotice {
        core.TaskNotice(chatId: "websocket:a", outcome: core.TaskOutcome.completed, key: key)
    }

    @Test func oneNoticeIsOneBanner() async {
        let posting = FakePosting()
        let centre = TaskNoticeCenter(posting: posting)

        await centre.deliver(notice())
        await centre.deliver(notice())

        #expect(posting.posted.count == 1, "the same event is not a second banner")
    }

    @Test func aRefusedBannerIsRemembered() async {
        let posting = FakePosting()
        posting.state = core.NotificationPermission.notasked
        posting.accepts = false
        let centre = TaskNoticeCenter(posting: posting)

        #expect(await centre.deliver(notice()) == false)
        #expect(centre.missedNotice)
        #expect(centre.shouldAsk(permission: .notAsked), "a missed banner is the moment to ask")
    }

    @Test func thePermissionIsNotAskedBeforeABannerWentMissing() {
        let centre = TaskNoticeCenter(posting: FakePosting())

        #expect(centre.shouldAsk(permission: .notAsked) == false)
    }

    @Test func theAnswerClearsTheMissAndForgetsTheKey() async {
        let posting = FakePosting()
        posting.state = core.NotificationPermission.notasked
        posting.accepts = false
        let centre = TaskNoticeCenter(posting: posting)
        await centre.deliver(notice())

        posting.state = core.NotificationPermission.notasked
        posting.accepts = true
        let after = await centre.askIfJustified()

        #expect(posting.requests == 1)
        #expect(centre.missedNotice == false)
        #expect(after == .granted)
        // The banner the answer was for is worth another try.
        #expect(await centre.deliver(notice()))
    }

    @Test func aRefusalIsNeverAskedAgain() async {
        // The system refuses to draw the dialog twice; the honest offer is the phone's own screen.
        let posting = FakePosting()
        posting.state = core.NotificationPermission.denied
        posting.accepts = false
        let centre = TaskNoticeCenter(posting: posting)
        await centre.deliver(notice())

        await centre.askIfJustified()

        #expect(posting.requests == 0)
        #expect(centre.shouldAsk(permission: .denied) == false)
    }
}
