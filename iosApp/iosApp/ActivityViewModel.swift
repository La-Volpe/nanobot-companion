import Foundation

/// The activity cluster per turn: what nanobot thought, ran and changed, folded into one
/// row that opens on a tap.
///
/// Two rules live here rather than in the view. A running turn shows its work and a finished
/// turn folds it, which is the design's own behaviour (`finishRun` sets `data-open="false"`).
/// A reader's tap wins over both, and a failure overrides the fold: a turn that went wrong
/// should not hide the reason it did.
@MainActor
final class ActivityViewModel: ObservableObject {

    @Published private(set) var clusters: [String: ActivityCluster] = [:]

    /// The injected clock, so "Thought for 8s" is a computed value a test can pin.
    private let now: () -> Int64
    /// Turns the reader deliberately opened or closed; theirs, until the turn ends.
    private var overrides: [String: Bool] = [:]
    /// Rows whose details the reader opened, by row id. A row starts folded, one line, like the
    /// web's `ActivityStep`, and a tap is what shows the arguments and the output.
    private var openRows: Set<String> = []

    init(now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.now = now
    }

    /// The thread's turns that have something to show, in thread order.
    var turnIds: [String] { orderedIds }

    private var orderedIds: [String] = []

    func cluster(for turnId: String) -> ActivityCluster? { clusters[turnId] }

    func update(thread: ChatThreadState) {
        var next: [String: ActivityCluster] = [:]
        for turn in thread.turns where !turn.activity.isEmpty {
            next[turn.id] = cluster(for: turn, nowMs: now())
        }
        orderedIds = thread.turns.map(\.id).filter { next[$0] != nil }
        clusters = next
        // An override only lasts as long as the turn it was made on: once the turn is out of
        // the thread there is nothing left to remember.
        overrides = overrides.filter { next[$0.key] != nil }
        openRows.formIntersection(next.values.flatMap { $0.items.map(\.id) })
    }

    func toggle(turnId: String) {
        guard let cluster = clusters[turnId] else { return }
        overrides[turnId] = !cluster.isOpen
        clusters[turnId] = cluster.opening(!cluster.isOpen)
    }

    func toggleDetails(itemId: String) {
        let wasOpen = openRows.remove(itemId) != nil
        if !wasOpen { openRows.insert(itemId) }
        clusters = clusters.mapValues { cluster in
            guard cluster.items.contains(where: { $0.id == itemId }) else { return cluster }
            return cluster.decorating(itemId: itemId, open: openRows.contains(itemId))
        }
    }

    private func cluster(for turn: ChatTurnState, nowMs: Int64) -> ActivityCluster {
        let steps = turn.activity.filter { item in
            switch item.kind {
            case .tool, .fileEdit: true
            case .reasoning, .search: false
            }
        }.count
        let files = turn.activity.filter { item in
            if case .fileEdit = item.kind { return true }
            return false
        }.count
        let failed = turn.activity.contains { item in
            switch item.kind {
            case let .tool(_, _, status, _): status == .failed
            case let .fileEdit(state): state.status == .failed
            case .reasoning, .search: false
            }
        }

        let status: String
        let headline: String
        if turn.running {
            status = "running"
            headline = "Thinking"
        } else {
            status = failed ? "failed" : "done"
            headline = Self.finishedHeadline(startedAtMs: turn.startedAtMs, nowMs: nowMs, steps: steps, files: files)
        }

        // A reader who has said what they want wins; otherwise run open, fold finished,
        // and never fold a failure they have not seen.
        let isOpen = overrides[turn.id] ?? (turn.running || failed)

        return ActivityCluster(
            turnId: turn.id,
            headline: headline,
            statusText: status,
            items: turn.activity.map { item in
                openRows.contains(item.id) ? item.openingDetails() : item
            },
            isOpen: isOpen,
            hasFailure: failed,
            steps: steps
        )
    }

    /// `Thought for 8s · 3 steps · 1 file changed`, and no clause for a count of zero.
    private static func finishedHeadline(startedAtMs: Int64?, nowMs: Int64, steps: Int, files: Int) -> String {
        let elapsed = max(0, nowMs - (startedAtMs ?? nowMs))
        // Whole seconds, never "0s": a turn that took less than a second still took a moment.
        let seconds = max(1, Int(elapsed / 1000))
        var parts = ["Thought for \(seconds)s"]
        parts.append("\(steps) \(steps == 1 ? "step" : "steps")")
        if files > 0 { parts.append("\(files) \(files == 1 ? "file changed" : "files changed")") }
        return parts.joined(separator: " · ")
    }
}

struct ActivityCluster: Equatable {
    let turnId: String
    let headline: String
    /// `running`, `done` or `failed`: the design's own three words.
    let statusText: String
    let items: [ChatActivityItem]
    let isOpen: Bool
    let hasFailure: Bool
    let steps: Int

    /// Every web-search hit in the cluster, in arrival order.
    var sourceChips: [SourceChip] {
        items.flatMap { item -> [SourceChip] in
            if case let .search(_, sources) = item.kind { return sources.map(SourceChip.init(url:)) }
            return []
        }
    }

    /// One row with its disclosure opened, which is the reader's own choice.
    func decorating(itemId: String, open: Bool) -> ActivityCluster {
        ActivityCluster(
            turnId: turnId,
            headline: headline,
            statusText: statusText,
            items: items.map { item in
                guard item.id == itemId else { return item }
                var copy = item
                copy.detailsOpen = open
                return copy
            },
            isOpen: isOpen,
            hasFailure: hasFailure,
            steps: steps
        )
    }

    func opening(_ open: Bool) -> ActivityCluster {
        ActivityCluster(
            turnId: turnId,
            headline: headline,
            statusText: statusText,
            items: items,
            isOpen: open,
            hasFailure: hasFailure,
            steps: steps
        )
    }
}
