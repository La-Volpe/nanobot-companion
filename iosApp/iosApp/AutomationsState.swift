import Foundation

/// One row of the automations page, in Swift terms: the design's card is an icon, a name, the
/// schedule, the next run, and either a switch or a "System" flag.
struct AutomationRow: Identifiable, Equatable {
    let id: String
    let name: String
    let schedule: String
    let detail: String
    let enabled: Bool
    /// A gateway-owned job (heartbeat, dream): the gateway answers 403, so no switch is drawn.
    let isSystem: Bool
    var pending: Bool = false
}

/// What the page draws. "The read has not answered" and "the gateway has nothing scheduled" are
/// two different screens, and the owner reported exactly that: an unread page used to be blank,
/// which reads as an empty one.
enum AutomationsPage: Equatable { case loading, empty, rows, failed }

/// The four things a row can do. Creating one is not among them — the gateway has no create
/// endpoint, so the page ends with a button that asks the agent instead.
enum AutomationAct: Equatable {
    case run
    case pause
    case resume
    case delete
}

/// What the page needs from the core. A protocol so the tests describe behaviour instead of a
/// socket, the same shape `ChatDriving` and `GatewayConnecting` use.
protocol AutomationsDriving: AnyObject {
    /// The rows, or nil when the read failed (the page then keeps what it had).
    func load() async -> [AutomationRow]?
    /// The gateway's refusal sentence, or nil when the act went through.
    func act(_ act: AutomationAct, id: String) async -> String?
}

/// The automations page (I2b). It lists, runs, pauses and deletes; it never invents a schedule.
@MainActor
final class AutomationsViewModel: ObservableObject {
    @Published private(set) var rows: [AutomationRow] = []
    @Published private(set) var loaded = false
    @Published private(set) var isLoading = false
    /// One sentence for the last refusal, the way every other failure in this app is said.
    @Published private(set) var message: String?

    private let driver: AutomationsDriving

    init(driver: AutomationsDriving) {
        self.driver = driver
    }

    /// One truth for "the page really is empty": a failed read is not the same sentence.
    var isEmpty: Bool { page == .empty }

    /// What the list area draws: a skeleton, the jobs, the "none" sentence, or the failure sentence.
    var page: AutomationsPage {
        // Rows already on screen outrank any failure: a stale page beats a blank one.
        if !rows.isEmpty { return .rows }
        if isLoading { return .loading }
        if message != nil { return .failed }
        if loaded { return .empty }
        return .loading
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        guard let fresh = await driver.load() else {
            message = "Could not read the automations from the gateway."
            return
        }
        rows = fresh
        loaded = true
        message = nil
    }

    /// The switch on a row: pause keeps the schedule, resume starts following it again.
    func setEnabled(_ id: String, _ enabled: Bool) async {
        await perform(enabled ? .resume : .pause, id: id)
    }

    func runNow(_ id: String) async {
        await perform(.run, id: id)
    }

    func delete(_ id: String) async {
        await perform(.delete, id: id)
    }

    private func perform(_ act: AutomationAct, id: String) async {
        guard let index = rows.firstIndex(where: { $0.id == id }) else { return }
        rows[index].pending = true
        let refusal = await driver.act(act, id: id)
        // The page re-reads rather than guessing the job's new state: the gateway owns it, and a
        // row that flipped locally while the gateway refused would be a lie.
        await load()
        message = refusal
    }
}
