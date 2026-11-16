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

    /// Every switch on: the tests that are not about the switches are about the posting.
    private let allOn: () -> core.NoticeSwitches = {
        core.NoticeSwitches(finished: true, failedOrStopped: true, needsAttention: true)
    }

    private func notice(
        _ key: String = "websocket:a|turn:turn-1|Completed",
        outcome: core.TaskOutcome = core.TaskOutcome.completed
    ) -> core.TaskNotice {
        core.TaskNotice(chatId: "websocket:a", outcome: outcome, key: key)
    }

    @Test func oneNoticeIsOneBanner() async {
        let posting = FakePosting()
        let centre = TaskNoticeCenter(posting: posting, switches: allOn)

        await centre.deliver(notice())
        await centre.deliver(notice())

        #expect(posting.posted.count == 1, "the same event is not a second banner")
    }

    @Test func aRefusedBannerIsRemembered() async {
        let posting = FakePosting()
        posting.state = core.NotificationPermission.notasked
        posting.accepts = false
        let centre = TaskNoticeCenter(posting: posting, switches: allOn)

        #expect(await centre.deliver(notice()) == false)
        #expect(centre.missedNotice)
        #expect(centre.shouldAsk(permission: .notAsked), "a missed banner is the moment to ask")
    }

    @Test func thePermissionIsNotAskedBeforeABannerWentMissing() {
        let centre = TaskNoticeCenter(posting: FakePosting(), switches: allOn)

        #expect(centre.shouldAsk(permission: .notAsked) == false)
    }

    @Test func theAnswerClearsTheMissAndForgetsTheKey() async {
        let posting = FakePosting()
        posting.state = core.NotificationPermission.notasked
        posting.accepts = false
        let centre = TaskNoticeCenter(posting: posting, switches: allOn)
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

    /// T43: the switches have to gate something. A reader who turned "Needs attention" off is not
    /// posted a recovery, and the suppressed notice is not remembered either — turning the switch
    /// back on lets the next one through, which is what a switch means.
    @Test func aSwitchThatIsOffSilencesTheNoticeItNames() async {
        let posting = FakePosting()
        var switches = core.NoticeSwitches(finished: true, failedOrStopped: true, needsAttention: false)
        let centre = TaskNoticeCenter(posting: posting, switches: { switches })
        let attention = notice(
            "websocket:a|recovery:r-1|NeedsAttention",
            outcome: core.TaskOutcome.needsattention
        )

        #expect(await centre.deliver(attention) == false, "the switch the reader turned off says no")
        #expect(posting.posted.isEmpty)
        #expect(centre.missedNotice == false, "a switch is not a missing permission: nothing to ask for")

        switches = core.NoticeSwitches(finished: true, failedOrStopped: true, needsAttention: true)
        #expect(await centre.deliver(attention), "the switch was never a memory of this notice")
        #expect(posting.posted.count == 1)
    }

    /// The name of a switch is the set of outcomes it gates: stopping a turn is not finishing it.
    @Test func theFailedSwitchCoversAFailureAndAStop() async {
        let posting = FakePosting()
        let centre = TaskNoticeCenter(
            posting: posting,
            switches: { core.NoticeSwitches(finished: true, failedOrStopped: false, needsAttention: true) }
        )

        #expect(await centre.deliver(notice("websocket:a|turn:t-2|Failed", outcome: core.TaskOutcome.failed)) == false)
        #expect(await centre.deliver(notice("websocket:a|turn:t-3|Interrupted", outcome: core.TaskOutcome.interrupted)) == false)
        #expect(posting.posted.isEmpty, "both are the row the reader turned off")
    }

    @Test func aRefusalIsNeverAskedAgain() async {
        // The system refuses to draw the dialog twice; the honest offer is the phone's own screen.
        let posting = FakePosting()
        posting.state = core.NotificationPermission.denied
        posting.accepts = false
        let centre = TaskNoticeCenter(posting: posting, switches: allOn)
        await centre.deliver(notice())

        await centre.askIfJustified()

        #expect(posting.requests == 0)
        #expect(centre.shouldAsk(permission: .denied) == false)
    }
}
