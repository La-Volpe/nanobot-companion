import XCTest

/// The drawer's Automations row used to close the drawer and do nothing else. The page it opens
/// is the design's: a title bar, the Scheduled group, and one way out that asks the agent.
final class AutomationsPageTests: XCTestCase {

    func testTheDrawerRowOpensTheAutomationsPage() {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launch()

        app.buttons["chat.openDrawer"].tap()
        let row = app.buttons["drawer.automations"]
        XCTAssertTrue(row.waitForExistence(timeout: 5), "the drawer lost its automations row")
        row.tap()

        XCTAssertTrue(
            app.staticTexts["Automations"].waitForExistence(timeout: 5),
            "the row still does not open a page"
        )
        XCTAssertTrue(app.buttons["automations.ask"].exists, "the page lost its one way out")

        // And back out again, which is the only navigation the page has.
        app.buttons["automations.back"].tap()
        XCTAssertTrue(app.buttons["chat.openDrawer"].waitForExistence(timeout: 5))
    }
}
