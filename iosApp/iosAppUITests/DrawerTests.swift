import XCTest

/// The drawer flow a tap can prove (plan.md I2's gate): it opens over the chat, the search
/// field filters what is loaded, and a query that matches nothing says so — the states the
/// design artifact calls "searching" and "no matches".
final class DrawerTests: XCTestCase {

    private func connectedApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_SESSIONS"] = "samples"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launch()
        XCTAssertTrue(app.buttons["chat.openDrawer"].waitForExistence(timeout: 10), "the shell is up")
        return app
    }

    func testTheDrawerListsTheLoadedSessions() {
        let app = connectedApp()

        app.buttons["chat.openDrawer"].tap()

        XCTAssertTrue(app.textFields["drawer.search"].waitForExistence(timeout: 5), "the drawer slides over the chat")
        XCTAssertTrue(app.staticTexts["Session store batching"].exists)
        XCTAssertTrue(app.staticTexts["Deploy notes"].exists)
        XCTAssertTrue(app.buttons["drawer.automations"].exists, "automations are pinned to the bottom")
        XCTAssertTrue(app.buttons["drawer.archived"].exists, "archived is a row, not a filter")
    }

    func testSearchFiltersAndShowsTheNoMatchState() {
        let app = connectedApp()
        app.buttons["chat.openDrawer"].tap()

        let search = app.textFields["drawer.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("deploy")

        XCTAssertTrue(app.staticTexts["Deploy notes"].exists)
        XCTAssertFalse(app.staticTexts["Session store batching"].exists, "a query hides the rows it does not match")

        search.typeText(" something else")
        let empty = app.staticTexts["drawer.emptyState"]
        XCTAssertTrue(empty.waitForExistence(timeout: 5))
        XCTAssertTrue(empty.label.contains("No sessions match"), "the drawer says why it is empty: \(empty.label)")
    }

    func testSelectingASessionClosesTheDrawer() {
        let app = connectedApp()
        app.buttons["chat.openDrawer"].tap()

        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'drawer.row.'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()

        XCTAssertFalse(app.textFields["drawer.search"].waitForExistence(timeout: 2), "picking a session returns to the chat")
    }
}
