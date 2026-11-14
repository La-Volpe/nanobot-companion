import XCTest

/// The drawer opens by dragging from the leading edge, not only by pressing the button, and a
/// drag across it closes it again. The gesture has to be horizontal: a vertical drag on a long
/// answer must still scroll the thread.
final class DrawerGestureTests: XCTestCase {

    func testDraggingFromTheEdgeOpensTheDrawerAndDraggingBackClosesIt() {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launch()
        app.activate()

        let search = app.textFields["drawer.search"]
        XCTAssertFalse(search.exists, "the drawer starts closed")

        let screen = app.windows.firstMatch
        let edge = screen.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5))
        let middle = screen.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5))
        edge.press(forDuration: 0.05, thenDragTo: middle)

        XCTAssertTrue(search.waitForExistence(timeout: 5), "a drag from the edge did not open the drawer")

        // And back: a drag across the open drawer returns to the thread.
        let drawer = app.otherElements["drawer.scrim"]
        XCTAssertTrue(drawer.exists)
        middle.press(forDuration: 0.05, thenDragTo: edge)
        XCTAssertTrue(search.waitForNonExistence(timeout: 5), "a drag across the drawer did not close it")
    }

    func testAVerticalDragOnTheThreadDoesNotOpenTheDrawer() {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launch()
        app.activate()

        let screen = app.windows.firstMatch
        let top = screen.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.8))
        let bottom = screen.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.2))
        top.press(forDuration: 0.05, thenDragTo: bottom)

        XCTAssertFalse(app.textFields["drawer.search"].exists, "a vertical drag must not open the drawer")
    }
}
