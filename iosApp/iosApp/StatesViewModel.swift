import Foundation

/// The states that are not part of an answer (plan.md I8, C7.3): the connection dot and banner,
/// the notices, the goal strip, the subtask dock and a delivery that failed.
///
/// One owner per state is the rule this file exists for. The banner belongs to the connection
/// and nothing else; the goal is a strip and never a transcript row; a notice is a row and never
/// a banner; a settled subtask is not in the dock. Two surfaces showing the same state is how a
/// chat screen starts contradicting itself.

/// A notice as the core reduced it, in Swift's own words (the adapter maps `Notice`).
enum ThreadNotice: Equatable {
    case compaction(id: String, phase: String, reason: String?)
    case recovery(id: String, status: String, canContinue: Bool, reason: String?)
    case retry(id: String, attempt: Int, maxAttempts: Int?, errorKind: String?, retryAtMs: Int?)
    case goal(id: String, active: Bool, status: String?, summary: String?)
    case failure(id: String, errorKind: String?, message: String?)
    case presetFallback(id: String, modelName: String?)
    case sandbox(id: String, issue: String)

    var id: String {
        switch self {
        case let .compaction(id, _, _), let .recovery(id, _, _, _), let .retry(id, _, _, _, _),
             let .goal(id, _, _, _), let .failure(id, _, _), let .presetFallback(id, _), let .sandbox(id, _):
            id
        }
    }
}

/// A subtask the parent agent owns (the core's `Subagent`, as Swift sees it).
struct SubagentRow: Identifiable, Equatable {
    let id: String
    let label: String
    let state: String
    let iteration: Int
    let elapsedSeconds: Double

    var isActive: Bool { ["queued", "running", "stopping"].contains(state) }

    /// `Running · step 2`: the gateway does not say how many steps there will be, so this does
    /// not invent a denominator.
    var caption: String {
        let word = state == "queued" ? "Queued" : state == "stopping" ? "Stopping" : "Running"
        return "\(word) · step \(iteration)"
    }
}

/// What the connection looks like: the dot's five colours and the banner's one line.
enum ConnectionStatus: Equatable {
    case idle
    case connecting
    case reconnecting
    case open
    case offline
    case failed(ConnectionFailureKind, detail: String?)
}

enum ConnectionDot: Equatable {
    case idle
    case connecting
    case reconnecting
    case open
    case offline
    case failed
}

struct ConnectionBanner: Equatable {
    let text: String
    let showsRetry: Bool
}

enum NoticeKind: Equatable {
    case recovery
    case retry
    case compaction
    case sandbox
    case failure
    case presetFallback
    case goal
}

struct StateRow: Identifiable, Equatable {
    struct Action: Equatable {
        let title: String
        let identifier: String
    }

    let id: String
    let kind: NoticeKind
    let text: String
    let detail: String?
    let actions: [Action]
    let isError: Bool
}

struct DeliveryFailure: Equatable {
    let text: String
    var resending: Bool

    var title: String { "Not sent" }
    var detail: String { "The gateway did not take that message." }
}

/// One subtask's dock row, as the view draws it.
struct SubagentDockRow: Identifiable, Equatable {
    let id: String
    let label: String
    let caption: String
}

/// The three commands the states surfaces send to the chat: drop a client-owned notice, answer
/// a recovery, or stop a subtask.
@MainActor
protocol StateDriving: AnyObject {
    func dismissNotice(id: String)
    func recovery(action: String) async
    func cancelSubagent(id: String) async
}

@MainActor
final class NoopStates: StateDriving {
    func dismissNotice(id: String) {}
    func recovery(action: String) async {}
    func cancelSubagent(id: String) async {}
}

@MainActor
final class StatesViewModel: ObservableObject {

    @Published private(set) var dot: ConnectionDot = .idle
    @Published private(set) var banner: ConnectionBanner?
    @Published private(set) var rows: [StateRow] = []
    @Published private(set) var goalStrip: String?
    @Published private(set) var dock: [SubagentDockRow] = []
    @Published private(set) var delivery: DeliveryFailure?
    /// Which failure the debug menu injected, if any (DEBUG; nil in every other path).
    private(set) var injectedFailure: ConnectionFailureKind?

    /// Commands the shell owns: dismissing a client-owned notice, resending a failed message,
    /// continuing or dismissing a recovery, stopping a subtask.
    var onDismiss: (String) -> Void = { _ in }
    var onResend: (String) -> Void = { _ in }
    var onRecovery: (String, String) -> Void = { _, _ in }
    var onCancelSubagent: (String) -> Void = { _ in }
    var onRetryConnection: () -> Void = {}

    private let now: () -> Int64
    /// Dismissed client-owned notices; the core drops its own copy, and this is the same answer
    /// before the round trip comes back.
    private var dismissed: Set<String> = []

    init(now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.now = now
    }

    func update(
        connection: ConnectionStatus,
        notices: [ThreadNotice],
        subagents: [SubagentRow] = [],
        nowMs: Int64,
        wasConnected: Bool = false
    ) {
        apply(connection: connection, wasConnected: wasConnected)
        update(notices: notices, subagents: subagents, nowMs: nowMs)
    }

    /// The thread's own states, kept apart from the connection so the two cannot overwrite each
    /// other: the socket decides the banner, the thread decides the rows.
    func update(notices: [ThreadNotice], subagents: [SubagentRow] = [], nowMs: Int64) {
        rows = Self.rows(notices: notices, nowMs: nowMs, dismissed: dismissed)
        goalStrip = notices.compactMap { notice -> String? in
            guard case let .goal(_, active, _, summary) = notice, active else { return nil }
            return summary
        }.first
        dock = subagents.filter(\.isActive).map {
            SubagentDockRow(id: $0.id, label: $0.label, caption: $0.caption)
        }
    }

    /// The connection on its own, for a screen that has no thread yet.
    func update(connection: ConnectionStatus, wasConnected: Bool = false) {
        apply(connection: connection, wasConnected: wasConnected)
    }

    // MARK: - delivery

    func deliveryFailed(text: String) {
        delivery = DeliveryFailure(text: text, resending: false)
    }

    func deliverySucceeded() {
        delivery = nil
    }

    func resend() async {
        guard let delivery else { return }
        self.delivery = DeliveryFailure(text: delivery.text, resending: true)
        onResend(delivery.text)
    }

    // MARK: - notices

    func dismiss(rowId: String) {
        dismissed.insert(rowId)
        rows.removeAll { $0.id == rowId }
        onDismiss(rowId)
    }

    func perform(_ action: StateRow.Action, in row: StateRow) {
        if action.identifier == "dismiss" {
            dismiss(rowId: row.id)
        } else {
            onRecovery(row.id, action.identifier)
        }
    }

    func cancelSubagent(id: String) {
        onCancelSubagent(id)
        dock.removeAll { $0.id == id }
    }

    func retryConnection() {
        onRetryConnection()
    }

    // MARK: - internals

    private func apply(connection: ConnectionStatus, wasConnected: Bool) {
        switch connection {
        case .idle:
            dot = .idle
            banner = nil
        case .connecting:
            dot = .connecting
            banner = ConnectionBanner(text: "Connecting…", showsRetry: false)
        case .open:
            dot = .open
            banner = nil
        case .offline:
            // A socket that was up and dropped is reconnecting; one that never connected is off.
            dot = wasConnected ? .reconnecting : .offline
            banner = ConnectionBanner(
                text: wasConnected ? "Reconnecting…" : "Disconnected. Sends are held.",
                showsRetry: !wasConnected
            )
        case .reconnecting:
            dot = .reconnecting
            banner = ConnectionBanner(text: "Reconnecting…", showsRetry: false)
        case let .failed(kind, detail):
            dot = .failed
            // The sentence is the taxonomy's, one per kind: a socket the system suspended fails as
            // `unreachable` or `timeout`, and one generic "token expired" would send the owner
            // hunting for a key that is fine (C7.4). "Try again" stays on every failure because
            // the design draws it under every failed handshake; whether the retry can succeed is
            // the taxonomy's answer, and onboarding is the screen that reads it (`offersRetry`).
            banner = ConnectionBanner(text: ConnectionCopy.text(for: kind, detail: detail), showsRetry: true)
        }
    }

    /// The precedence table: a decision to make first, then a retry with a clock, then the quiet
    /// ones, then a model fallback.
    private static func rows(notices: [ThreadNotice], nowMs: Int64, dismissed: Set<String>) -> [StateRow] {
        var rows: [StateRow] = []
        for notice in notices where !dismissed.contains(notice.id) {
            switch notice {
            case let .recovery(id, _, canContinue, reason):
                rows.append(
                    StateRow(
                        id: id,
                        kind: .recovery,
                        text: reason ?? "A task was interrupted.",
                        detail: canContinue ? "The gateway can restore its saved context." : nil,
                        actions: canContinue
                            ? [StateRow.Action(title: "Continue", identifier: "continue"), StateRow.Action(title: "Dismiss", identifier: "dismiss")]
                            : [StateRow.Action(title: "Dismiss", identifier: "dismiss")],
                        isError: false
                    )
                )
            case let .retry(id, attempt, maxAttempts, errorKind, retryAtMs):
                // Rounded up: with half a second left the row still says "1s", and it never
                // says "0s" while the gateway is still waiting.
                let seconds = retryAtMs.map { max(0, Int((Int64($0) - nowMs + 999) / 1000)) }
                rows.append(
                    StateRow(
                        id: id,
                        kind: .retry,
                        text: "Model \(errorKind == "timeout" ? "timed out" : (errorKind ?? "error")) · retrying in \(seconds ?? 0)s",
                        detail: maxAttempts.map { "attempt \(attempt) of \($0)" } ?? "attempt \(attempt)",
                        actions: [],
                        isError: false
                    )
                )
            case let .compaction(id, phase, _):
                rows.append(
                    StateRow(
                        id: id,
                        kind: .compaction,
                        text: compactionWording(phase),
                        detail: nil,
                        actions: [],
                        isError: phase == "failed"
                    )
                )
            case let .sandbox(id, issue):
                rows.append(
                    StateRow(
                        id: id,
                        kind: .sandbox,
                        text: sandboxWording(issue),
                        detail: nil,
                        actions: [StateRow.Action(title: "Dismiss", identifier: "dismiss")],
                        isError: true
                    )
                )
            case let .failure(id, errorKind, message):
                rows.append(
                    StateRow(
                        id: id,
                        kind: .failure,
                        text: message ?? "The turn failed.",
                        detail: errorKind,
                        actions: [],
                        isError: true
                    )
                )
            case let .presetFallback(id, modelName):
                rows.append(
                    StateRow(
                        id: id,
                        kind: .presetFallback,
                        text: "\(modelName ?? "Another model") answered instead of the preset you chose.",
                        detail: nil,
                        actions: [],
                        isError: false
                    )
                )
            case .goal:
                // The goal is a strip above the composer, never a row (one owner per state).
                continue
            }
        }
        // The precedence table: a decision first, then a retry with a clock, then the quiet
        // ones, then a model fallback. Two runs of the same notices produce the same order.
        return rows.sorted { precedence($0.kind) < precedence($1.kind) }
    }

    private static func precedence(_ kind: NoticeKind) -> Int {
        switch kind {
        case .recovery: 0
        case .retry: 1
        case .compaction: 2
        case .sandbox: 3
        case .failure: 4
        case .presetFallback: 5
        case .goal: 6
        }
    }

    private static func compactionWording(_ phase: String) -> String {
        switch phase {
        case "started": "Compacting context…"
        case "failed": "Context compaction failed. The gateway kept the old context."
        case "cancelled": "Context compaction was cancelled."
        default: "Context compacted to keep the conversation going."
        }
    }

    private static func sandboxWording(_ issue: String) -> String {
        switch issue {
        case "sandbox_launcher_missing": "Shell tools are paused: the sandbox launcher is not installed."
        case "sandbox_path_not_absolute", "sandbox_not_executable", "sandbox_path_unavailable":
            "Shell tools are paused: the sandbox path is not usable."
        case "sandbox_agent_writable", "sandbox_unprotected_installation":
            "Shell tools are paused: the sandbox is writable by the agent."
        default: "Shell tools are paused: the sandbox did not start."
        }
    }
}

/// What the DEBUG menu can put the app into. G2 asks for the eight failures to be reachable by
/// hand, not only by launch environment, so this is the list the menu draws.
enum DebugState {

    struct FailureScenario: Identifiable, Equatable {
        let kind: ConnectionFailureKind
        let title: String
        let detail: String?

        var id: String { kind.rawValue }
    }

    /// The eight taxonomy entries, with the detail the two that carry one need to be specific.
    static let failureScenarios: [FailureScenario] = [
        FailureScenario(kind: .authFailed, title: "Auth failed", detail: nil),
        FailureScenario(kind: .tlsRejected, title: "TLS rejected", detail: nil),
        FailureScenario(kind: .unreachable, title: "Unreachable", detail: nil),
        FailureScenario(kind: .timeout, title: "Timeout", detail: nil),
        FailureScenario(kind: .protocolTooNew, title: "Protocol too new", detail: "2/1"),
        FailureScenario(kind: .missingCapability, title: "Missing capability", detail: "webui.subagents.v1"),
        FailureScenario(kind: .missingField, title: "Missing field", detail: "token"),
        FailureScenario(kind: .serverError, title: "Server error", detail: "503"),
    ]

    /// The chat states the artifact's sixteen chips inject, minus the ones this phase does not
    /// draw (diagram, preview, temporary chat: not in v1 by plan D7 and the design README).
    enum ChatState: String, CaseIterable, Identifiable {
        case offline
        case reconnecting
        case compaction
        case recovery
        case sandbox
        case subagent
        case queued
        case notSent

        var id: String { rawValue }

        var title: String {
            switch self {
            case .offline: "Offline"
            case .reconnecting: "Reconnecting"
            case .compaction: "Compaction"
            case .recovery: "Recovery"
            case .sandbox: "Sandbox"
            case .subagent: "Subagent task"
            case .queued: "Queued prompt"
            case .notSent: "Not sent"
            }
        }
    }
}

#if DEBUG
extension StatesViewModel {

    /// Put the app into one of the eight failures, with the taxonomy's own sentence.
    func debugFail(_ kind: ConnectionFailureKind, detail: String?) {
        injectedFailure = kind
        // Nothing patched after the fact: the menu's whole purpose is to show what the socket
        // would, so a banner written here could hide a wrong one written above.
        update(connection: .failed(kind, detail: detail), notices: [], nowMs: 0)
    }

    /// One of the chat states, injected into the same state objects the live socket fills.
    func debugInject(_ state: DebugState.ChatState, composer: ComposerViewModel) {
        injectedFailure = nil
        switch state {
        case .offline:
            update(connection: .offline, notices: [], nowMs: 0, wasConnected: false)
        case .reconnecting:
            update(connection: .reconnecting, notices: [], nowMs: 0, wasConnected: true)
        case .compaction:
            update(connection: .open, notices: [.compaction(id: "debug-compaction", phase: "succeeded", reason: nil)], nowMs: 0)
        case .recovery:
            update(
                connection: .open,
                notices: [.recovery(id: "debug-recovery", status: "awaiting_user", canContinue: true, reason: "A task was interrupted while writing.")],
                nowMs: 0
            )
        case .sandbox:
            update(connection: .open, notices: [.sandbox(id: "debug-sandbox", issue: "sandbox_launcher_missing")], nowMs: 0)
        case .subagent:
            update(
                connection: .open,
                notices: [],
                subagents: [SubagentRow(id: "debug-sub", label: "audit attachment limits", state: "running", iteration: 2, elapsedSeconds: 4.5)],
                nowMs: 0
            )
        case .queued:
            update(connection: .open, notices: [], nowMs: 0)
            composer.update(snapshot: ComposerSnapshot(queued: "Then check whether the 6 MiB cap is enforced client-side too"))
        case .notSent:
            update(connection: .open, notices: [], nowMs: 0)
            deliveryFailed(text: "One more: does the queue survive a reload?")
        }
    }

    func debugClear() {
        injectedFailure = nil
        update(connection: .open, notices: [], nowMs: 0)
        deliverySucceeded()
    }
}
#endif
