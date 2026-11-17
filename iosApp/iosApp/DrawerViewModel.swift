import Foundation

/// The drawer's rows and its three states (design artifact `data-view="drawer"`).
/// The gateway hands the rows down; what a search field does to them, and what the drawer says
/// when there is nothing to show, is a decision worth testing, so it lives here and not in a view.
@MainActor
final class DrawerViewModel: ObservableObject {

    enum ListState: Equatable {
        /// Nothing has arrived yet: a spinner, not "no sessions".
        case loading
        /// Loaded with no rows at all: the drawer has never had a chat.
        case empty
        /// The read did not answer and there is nothing to fall back on: the app's problem,
        /// not the gateway's.
        case failed
        /// Loaded, rows exist, the query matched none of them.
        case noMatches(query: String)
        case rows([SessionSummary])
    }

    @Published private(set) var rows: [SessionSummary] = []
    @Published private(set) var loaded = false
    /// The last read did not answer: kept apart from `loaded` so the drawer cannot call it "none".
    @Published private(set) var failed = false
    @Published var searchText: String = ""
    @Published var showsArchived = false

    init(rows: [SessionSummary] = [], loaded: Bool = false, failed: Bool = false) {
        self.rows = rows
        self.loaded = loaded
        self.failed = failed
    }

    func update(rows: [SessionSummary], loaded: Bool, failed: Bool = false) {
        self.rows = rows
        self.loaded = loaded
        self.failed = failed
    }

    var isSearching: Bool { !searchText.isEmpty }

    /// Search is a case-insensitive substring over what the row shows (title and preview), the
    /// same fields the WebUI searches in the sessions it has already loaded (design README:
    /// "Search matches the webui behaviour of searching loaded content only").
    var matches: [SessionSummary] {
        guard isSearching else { return rows }
        let needle = searchText.lowercased()
        return rows.filter { row in
            row.displayTitle.lowercased().contains(needle) || row.preview.lowercased().contains(needle)
        }
    }

    var state: ListState {
        if !loaded { return .loading }
        // Rows from an earlier read still beat nothing: a stale drawer is only a failure when
        // there is nothing left to show (the core keeps them for exactly this reason).
        if failed, rows.isEmpty { return .failed }
        if rows.isEmpty { return .empty }
        if isSearching && matches.isEmpty { return .noMatches(query: searchText) }
        return .rows(matches)
    }

    /// What the empty and no-match lines say. The drawer keeps the archived row and the
    /// automations entry visible in every state, because they are not sessions.
    var emptyMessage: String? {
        switch state {
        case .loading, .failed, .rows: nil
        case .empty: "No sessions yet. Start one from the chat."
        case let .noMatches(query): "No sessions match “\(query)”."
        }
    }

    /// What a failed read says instead of a verdict about the gateway. Nil in every other state.
    var failureMessage: String? {
        state == .failed ? "Could not read your sessions from the gateway." : nil
    }

    /// Sessions are shown newest first; the gateway already sorts them, and a client that
    /// re-sorted by a string timestamp would put them in the wrong order, because `updated_at` is
    /// an ISO-8601 string.
    func ordered() -> [SessionSummary] {
        rows
    }
}
