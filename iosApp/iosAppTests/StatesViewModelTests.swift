import Foundation
import Testing
@testable import iosApp

/// I8's states: one connection banner, one row per notice, one dock, one goal strip, one
/// delivery failure — and each of them owned by exactly one place. The plan's red line is that
/// ownership: two surfaces showing the same state is how a chat screen starts contradicting
/// itself.
@MainActor
struct StatesViewModelTests {

    private func noticeCompaction(phase: String = "succeeded") -> ThreadNotice {
        .compaction(id: "cmp-1", phase: phase, reason: "context_pressure")
    }

    private func noticeRecovery(canContinue: Bool = true) -> ThreadNotice {
        .recovery(id: "rec-1", status: "awaiting_user", canContinue: canContinue, reason: "task interrupted")
    }

    private func noticeRetry() -> ThreadNotice {
        .retry(id: "retry-1", attempt: 2, maxAttempts: 3, errorKind: "timeout", retryAtMs: 9_000)
    }

    private func noticeGoal() -> ThreadNotice {
        .goal(id: "goal-1", active: true, status: "active", summary: "Shipping batching")
    }

    private func noticeSandbox() -> ThreadNotice {
        .sandbox(id: "sandbox-1", issue: "sandbox_launcher_missing")
    }

    private func model() -> StatesViewModel {
        StatesViewModel(now: { 5_000 })
    }

    /// The plan's red line: every state has one owner, and the order is the precedence table.
    @Test func eachStateHasOneOwner() {
        let model = model()

        model.update(
            connection: .open,
            notices: [noticeGoal(), noticeSandbox(), noticeCompaction(), noticeRetry(), noticeRecovery()],
            nowMs: 5_000
        )

        // One owner per kind: five notices, five rows, no duplicates.
        #expect(model.rows.filter { $0.kind == .recovery }.count == 1)
        #expect(model.rows.filter { $0.kind == .retry }.count == 1)
        #expect(model.rows.filter { $0.kind == .compaction }.count == 1)
        #expect(model.rows.filter { $0.kind == .sandbox }.count == 1)

        // The precedence the design draws: a decision first, then a retry, then the quiet ones.
        #expect(model.rows.map(\.kind) == [.recovery, .retry, .compaction, .sandbox])

        // The goal is a strip, not a row in the transcript: one owner again.
        #expect(model.goalStrip == "Shipping batching")
        #expect(model.rows.allSatisfy { $0.kind != .goal })

        // And the banner belongs to the connection alone: a notice cannot raise it.
        #expect(model.banner == nil)
    }

    @Test func theConnectionBannerIsTheOnlyBanner() {
        let model = model()

        model.update(connection: .connecting, notices: [noticeRecovery()], nowMs: 0)
        #expect(model.banner?.text == "Connecting…")
        #expect(model.banner?.showsRetry == false)
        #expect(model.dot == .connecting)

        model.update(connection: .open, notices: [], nowMs: 0)
        #expect(model.banner == nil, "an open socket has nothing to say")
        #expect(model.dot == .open)

        model.update(connection: .offline, notices: [], nowMs: 0, wasConnected: true)
        #expect(model.dot == .reconnecting, "a socket that was up and is now down is reconnecting")
        #expect(model.banner?.text == "Reconnecting…")

        model.update(connection: .offline, notices: [], nowMs: 0, wasConnected: false)
        #expect(model.dot == .offline)
        #expect(model.banner?.text == "Disconnected. Sends are held.")
        #expect(model.banner?.showsRetry == true)

        model.update(connection: .failed(.authFailed, detail: nil), notices: [], nowMs: 0)
        #expect(model.dot == .failed)
        #expect(model.banner?.text == "That key was refused. Check the gateway's key and try again.")
        #expect(model.banner?.showsRetry == true)
    }

    /// One sentence per kind, from the taxonomy. A socket iOS suspended in the background fails
    /// as `unreachable` or `timeout`, and telling its owner the token expired sends them hunting
    /// for a key that is fine (C7.4).
    @Test func theBannerIsTheFailuresOwnSentence() {
        for kind in ConnectionFailureKind.allCases {
            for detail in [nil, "503"] as [String?] {
                let model = model()
                model.update(connection: .failed(kind, detail: detail), notices: [], nowMs: 0)
                #expect(
                    model.banner?.text == ConnectionCopy.text(for: kind, detail: detail),
                    "\(kind) with detail \(detail ?? "none") says its own sentence"
                )
            }
        }

        let model = model()
        model.update(connection: .failed(.unreachable, detail: nil), notices: [], nowMs: 0)
        #expect(model.banner?.text == "No gateway at that address. Check the address and that the gateway is running.")
        #expect(model.banner?.text.contains("token") == false, "a socket that went away is not a token problem")
    }

    /// The debug menu must not dress the state up: what it injects is what the socket raises, so
    /// a menu that patches the banner after the fact would hide exactly this defect.
    @Test func theDebugMenuShowsTheSameBannerTheSocketRaises() {
        for kind in ConnectionFailureKind.allCases {
            let injected = model()
            injected.debugFail(kind, detail: kind == .serverError ? "503" : nil)

            let live = model()
            live.update(connection: .failed(kind, detail: kind == .serverError ? "503" : nil), notices: [], nowMs: 0)

            #expect(injected.banner == live.banner, "\(kind) is the same banner either way")
        }
    }

    @Test func theRetryNoticeCountsDown() {
        let model = model()
        model.update(connection: .open, notices: [noticeRetry()], nowMs: 5_000)

        #expect(only(model.rows)?.text == "Model timed out · retrying in 4s")
        #expect(only(model.rows)?.detail == "attempt 2 of 3")

        model.update(connection: .open, notices: [noticeRetry()], nowMs: 8_500)
        #expect(only(model.rows)?.text == "Model timed out · retrying in 1s", "the countdown is derived from the frame's clock")
    }

    @Test func theRecoveryNoticeOffersContinueOnlyWhenTheGatewayCanRestore() {
        let model = model()

        model.update(connection: .open, notices: [noticeRecovery(canContinue: true)], nowMs: 0)
        #expect(only(model.rows)?.actions.map(\.title) == ["Continue", "Dismiss"])

        model.update(connection: .open, notices: [noticeRecovery(canContinue: false)], nowMs: 0)
        #expect(only(model.rows)?.actions.map(\.title) == ["Dismiss"], "no saved context, no Continue button")
    }

    @Test func theSubagentDockHoldsOnlyTheActiveSubtasks() {
        let model = model()

        model.update(
            connection: .open,
            notices: [],
            subagents: [
                SubagentRow(id: "sub-1", label: "audit attachment limits", state: "running", iteration: 2, elapsedSeconds: 4.5),
                SubagentRow(id: "sub-2", label: "write the report", state: "done", iteration: 6, elapsedSeconds: 30),
            ],
            nowMs: 0
        )

        #expect(model.dock.map(\.id) == ["sub-1"], "a settled subtask is not in the dock")
        #expect(only(model.dock)?.caption == "Running · step 2")
    }

    @Test func aCompactionHasOneWordingForItsPhase() {
        let model = model()
        model.update(connection: .open, notices: [noticeCompaction(phase: "started")], nowMs: 0)
        #expect(only(model.rows)?.text == "Compacting context…")

        model.update(connection: .open, notices: [noticeCompaction(phase: "succeeded")], nowMs: 0)
        #expect(only(model.rows)?.text == "Context compacted to keep the conversation going.")

        model.update(connection: .open, notices: [noticeCompaction(phase: "failed")], nowMs: 0)
        #expect(only(model.rows)?.text == "Context compaction failed. The gateway kept the old context.")
    }

    @Test func aDismissedSandboxWarningLeavesTheRow() {
        let model = model()
        model.update(connection: .open, notices: [noticeSandbox()], nowMs: 0)
        #expect(model.rows.count == 1)

        var dismissed: [String] = []
        model.onDismiss = { dismissed.append($0) }
        model.dismiss(rowId: try! #require(model.rows.first).id)

        #expect(dismissed == ["sandbox-1"], "the row asks the core to drop its own notice")
        #expect(model.rows.isEmpty, "and the row goes away")
    }

    @Test func aFailedDeliverySaysNotSentAndCanBeResent() async {
        let model = model()
        model.update(connection: .open, notices: [], nowMs: 0)

        model.deliveryFailed(text: "one more thing")
        #expect(model.delivery?.title == "Not sent")
        #expect(model.delivery?.detail == "The gateway did not take that message.")
        #expect(model.delivery?.text == "one more thing")

        var resent: [String] = []
        model.onResend = { resent.append($0) }
        await model.resend()

        #expect(resent == ["one more thing"])
        #expect(model.delivery?.resending == true, "the row says it is trying again")

        model.deliverySucceeded()
        #expect(model.delivery == nil)
    }

    @Test func theDebugMenuCanReachEveryOneOfTheEightFailures() {
        #expect(ConnectionFailureKind.allCases.count == 8)
        #expect(DebugState.failureScenarios.count == 8, "the menu lists the taxonomy, not a subset of it")
        #expect(Set(DebugState.failureScenarios.map(\.kind)) == Set(ConnectionFailureKind.allCases))
        #expect(DebugState.failureScenarios.allSatisfy { !$0.title.isEmpty })
    }
}

/// Swift's collections have no `single`: this is the same assertion, one item or nothing.
private func only<T>(_ items: [T]) -> T? {
    items.count == 1 ? items.first : nil
}
