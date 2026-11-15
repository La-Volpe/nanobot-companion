import Foundation
import Testing
@testable import iosApp

/// The drawer's branches (plan.md I2): what a query does to the rows, and which of the three
/// empty states it lands in. The plan's red line is `searchFiltersAndShowsNoMatchState`.
@MainActor
struct DrawerViewModelTests {

    private func row(_ title: String, preview: String = "", key: String = UUID().uuidString) -> SessionSummary {
        SessionSummary(key: key, chatId: key, title: title, preview: preview, updatedAtMs: 0)
    }

    @Test func searchFiltersAndShowsNoMatchState() {
        let model = DrawerViewModel(
            rows: [
                row("Session store batching", preview: "Rework the session store"),
                row("Deploy notes", preview: "nginx and tailscale"),
                row("Untitled"),
            ],
            loaded: true
        )

        #expect(model.state == .rows(model.rows), "an empty query shows every row")

        model.searchText = "store"
        #expect(model.state == .rows([model.rows[0]]), "the query matches the title")

        model.searchText = "TAILSCALE"
        #expect(model.state == .rows([model.rows[1]]), "search is case-insensitive and looks at the preview too")

        model.searchText = "nothing like this"
        #expect(model.state == .noMatches(query: "nothing like this"))
        #expect(model.emptyMessage == "No sessions match “nothing like this”.")
    }

    @Test func aFailedReadIsNotAnEmptyDrawer() {
        // `GET /api/sessions` answered with a 500. The core used to turn that into `loaded` with no
        // rows, and the drawer said "No sessions yet" about a gateway it never managed to read.
        let model = DrawerViewModel()
        model.update(rows: [], loaded: true, failed: true)

        #expect(model.state == .failed)
        #expect(model.emptyMessage == nil, "'no sessions yet' is a claim a failed read cannot make")
        #expect(model.failureMessage == "Could not read your sessions from the gateway.")
    }

    @Test func aDrawerThatIsLoadingSaysNothingRatherThanReadingAsEmpty() {
        let model = DrawerViewModel(rows: [row("One")], loaded: true)
        #expect(model.state == .rows(model.rows))

        model.update(rows: [], loaded: false)

        #expect(model.state == .loading, "a re-read is a skeleton, not a verdict")
        #expect(model.emptyMessage == nil)
        #expect(model.failureMessage == nil)
    }

    @Test func anUnloadedDrawerIsLoadingAndNeverSaysNoMatches() {
        let model = DrawerViewModel(rows: [], loaded: false)

        #expect(model.state == .loading)
        #expect(model.emptyMessage == nil, "a spinner is not a sentence")

        model.searchText = "anything"
        #expect(model.state == .loading, "searching before the rows arrived is still loading")
    }

    @Test func aLoadedDrawerWithNoSessionsSaysSo() {
        let model = DrawerViewModel(rows: [], loaded: true)

        #expect(model.state == .empty)
        #expect(model.emptyMessage == "No sessions yet. Start one from the chat.")
    }

    @Test func anUntitledRowStillHasAName() {
        #expect(row("").displayTitle == "Untitled session")
        #expect(row("Real title").displayTitle == "Real title")
    }

    @Test func theArchivedRowIsAlwaysAvailable() {
        let model = DrawerViewModel(rows: [row("One")], loaded: true)

        #expect(model.showsArchived == false, "the drawer opens on the live sessions")
        model.showsArchived = true
        #expect(model.state == .rows(model.rows), "archived is a row, not a filter, so it does not empty the list")
    }

    @Test func updatingWithNewRowsReplacesThemRatherThanAppending() {
        let model = DrawerViewModel(rows: [row("Old")], loaded: true)

        model.update(rows: [row("New")], loaded: true)

        #expect(model.rows.count == 1)
        #expect(model.rows.first?.title == "New")
    }
}
