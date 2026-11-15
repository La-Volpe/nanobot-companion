import Foundation
import Testing
@testable import iosApp

/// The automations page is a list plus four acts. These tests describe what the page does with
/// the gateway's answers: it never flips a row on its own, and it never hides a refusal.
@MainActor
struct AutomationsViewModelTests {

    final class FakeDriver: AutomationsDriving {
        var jobs: [AutomationRow]
        var refusal: String?
        var acts: [(AutomationAct, String)] = []
        var loads = 0
        var loadFails = false

        init(jobs: [AutomationRow]) { self.jobs = jobs }

        func load() async -> [AutomationRow]? {
            loads += 1
            return loadFails ? nil : jobs
        }

        func act(_ act: AutomationAct, id: String) async -> String? {
            acts.append((act, id))
            guard refusal == nil else { return refusal }
            // The gateway is the owner: the fake answers like it, by removing or flipping a job.
            switch act {
            case .delete: jobs.removeAll { $0.id == id }
            case .pause: jobs = jobs.map { $0.id == id ? with($0, enabled: false) : $0 }
            case .resume: jobs = jobs.map { $0.id == id ? with($0, enabled: true) : $0 }
            case .run: break
            }
            return nil
        }

        private func with(_ row: AutomationRow, enabled: Bool) -> AutomationRow {
            AutomationRow(id: row.id, name: row.name, schedule: row.schedule, detail: row.detail,
                          enabled: enabled, isSystem: row.isSystem)
        }
    }

    private func row(_ id: String, enabled: Bool = true, system: Bool = false) -> AutomationRow {
        AutomationRow(id: id, name: "Morning brief", schedule: "0 8 * * *",
                      detail: "Next · Fri 08:00", enabled: enabled, isSystem: system)
    }

    @Test func loadingFillsTheList() async {
        let driver = FakeDriver(jobs: [row("a"), row("b")])
        let model = AutomationsViewModel(driver: driver)

        await model.load()

        #expect(model.rows.map(\.id) == ["a", "b"])
        #expect(model.loaded)
        #expect(model.isEmpty == false)
        #expect(model.message == nil)
    }

    @Test func anEmptyGatewayIsNotAFailure() async {
        let model = AutomationsViewModel(driver: FakeDriver(jobs: []))

        await model.load()

        #expect(model.isEmpty, "the page must say 'no automations yet', not show an error")
        #expect(model.page == .empty)
        #expect(model.message == nil)
    }

    @Test func aPageThatHasNotBeenReadSaysNothingAboutHavingNone() {
        let model = AutomationsViewModel(driver: FakeDriver(jobs: [row("a")]))

        #expect(model.page == .loading, "before the read answers, the page is loading")
        #expect(model.isEmpty == false, "so the empty copy must not be drawn")
    }

    @Test func aFailedRefreshDoesNotSayTheGatewayHasNoAutomations() async {
        // The page loaded and the gateway said there is nothing scheduled. Then a refresh failed:
        // "No automations yet" is a claim a failed read cannot make.
        let driver = FakeDriver(jobs: [])
        let model = AutomationsViewModel(driver: driver)
        await model.load()
        #expect(model.page == .empty)

        driver.loadFails = true
        await model.load()

        #expect(model.page == .failed)
        #expect(model.message != nil, "and the sentence the page already has is what says so")
    }

    @Test func aFailedReadKeepsTheRowsItHad() async {
        let driver = FakeDriver(jobs: [row("a")])
        let model = AutomationsViewModel(driver: driver)
        await model.load()

        driver.loadFails = true
        await model.load()

        #expect(model.rows.count == 1, "an unreachable gateway must not blank the page")
        #expect(model.message != nil)
    }

    @Test func theSwitchPausesAndResumes() async {
        let driver = FakeDriver(jobs: [row("a", enabled: true)])
        let model = AutomationsViewModel(driver: driver)
        await model.load()

        await model.setEnabled("a", false)
        #expect(driver.acts.map(\.0) == [.pause])
        #expect(model.rows.first?.enabled == false, "the row follows the gateway, not the tap")

        driver.acts = []
        await model.setEnabled("a", true)
        #expect(driver.acts.map(\.0) == [.resume])
        #expect(model.rows.first?.enabled == true)
    }

    @Test func runNowAsksTheGatewayOnceAndReReads() async {
        let driver = FakeDriver(jobs: [row("a")])
        let model = AutomationsViewModel(driver: driver)
        await model.load()
        let loadsAfterOpen = driver.loads

        await model.runNow("a")

        #expect(driver.acts.map(\.0) == [.run])
        #expect(driver.loads == loadsAfterOpen + 1, "the page re-reads instead of guessing the new state")
    }

    @Test func deleteRemovesTheRowOnlyBecauseTheGatewaySaysSo() async {
        let driver = FakeDriver(jobs: [row("a"), row("b")])
        let model = AutomationsViewModel(driver: driver)
        await model.load()

        await model.delete("a")

        #expect(driver.acts.map(\.0) == [.delete])
        #expect(model.rows.map(\.id) == ["b"])
    }

    @Test func aRefusalIsSaidOutLoudAndTheRowStays() async {
        let driver = FakeDriver(jobs: [row("heartbeat", enabled: false, system: true)])
        driver.refusal = "This job belongs to the gateway and cannot be changed from here."
        let model = AutomationsViewModel(driver: driver)
        await model.load()

        await model.setEnabled("heartbeat", true)

        #expect(model.rows.count == 1)
        #expect(model.message == driver.refusal, "a 403 is said out loud, not swallowed")
    }
}
