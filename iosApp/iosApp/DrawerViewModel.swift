import Foundation

/// The drawer's rows and its three states (plan.md I2, design artifact `data-view="drawer"`).
/// The gateway hands the rows down; what a search field does to them, and what the drawer says
/// when there is nothing to show, is a decision worth testing — so it lives here, not in a view.
@MainActor
final class DrawerViewModel: ObservableObject {

    enum ListState: Equatable {
        /// Nothing has arrived yet: a spinner, not "no sessions".
        case loading
        /// Loaded with no rows at all: the drawer has never had a chat.
        case empty
        /// Loaded, rows exist, the query matched none of them.
        case noMatches(query: String)
        case rows([SessionSummary])
    }

    @Published private(set) var rows: [SessionSummary] = []
    @Published private(set) var loaded = false
    @Published var searchText: String = ""
    @Published var showsArchived = false

    init(rows: [SessionSummary] = [], loaded: Bool = false) {
        self.rows = rows
        self.loaded = loaded
    }

    func update(rows: [SessionSummary], loaded: Bool) {
        self.rows = rows
        self.loaded = loaded
    }

    var isSearching: Bool { !searchText.isEmpty }

    /// Search is a case-insensitive substring over what the row shows — title and preview, the
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
        if rows.isEmpty { return .empty }
        if isSearching && matches.isEmpty { return .noMatches(query: searchText) }
        return .rows(matches)
    }

    /// What the empty and no-match lines say. The drawer keeps the archived row and the
    /// automations entry visible in every state, because they are not sessions.
    var emptyMessage: String? {
        switch state {
        case .loading, .rows: nil
        case .empty: "No sessions yet. Start one from the chat."
        case let .noMatches(query): "No sessions match “\(query)”."
        }
    }

    /// Sessions are shown newest first; the gateway already sorts them, and a client that
    /// re-sorted by a string timestamp would put them in the wrong order (D15.2).
    func ordered() -> [SessionSummary] {
        rows
    }
}
