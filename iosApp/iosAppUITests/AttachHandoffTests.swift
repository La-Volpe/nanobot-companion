import XCTest

/// The attach sheet's three rows each open their own picker from one tap.
///
/// The bug the owner reported: every row closed the composer sheet and asked for the picker in
/// the same tap, and iOS will not present a second sheet while the first is still dismissing, so
/// the request was dropped until the overlay was closed by hand. What this suite proves is the
/// ordering: one tap on a row, nothing else, and the picker is there.
///
/// The rows are proved as far as a simulator allows. Photos and Files present the system's own
/// sheets, which XCUITest sees inside the app's tree. The camera is the simulator's simulated
/// camera (a lens, a shutter, no real sensor), so what is proved here is that the cover opens;
/// a photo taken with a real lens is the owner's phone.
final class AttachHandoffTests: XCTestCase {

    private func attachApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        app.launch()
        if !app.buttons["chat.voice"].waitForExistence(timeout: 10) {
            XCTFail("the composer did not come up. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    private func openAttachSheet(_ app: XCUIApplication, row: String) -> XCUIElement {
        app.buttons["composer.attach"].tap()
        let button = app.buttons["attach.\(row)"]
        if !button.waitForExistence(timeout: 5) {
            XCTFail("the \(row) row is not on the attach sheet. Tree:\n\(app.debugDescription)")
        }
        return button
    }

    func testThePhotosRowOpensThePickerOnOneTap() {
        let app = attachApp()

        openAttachSheet(app, row: "photos").tap()

        XCTAssertTrue(
            app.navigationBars["Photos"].waitForExistence(timeout: 6),
            "one tap on Photo or video opens the picker; nothing else is tapped. Tree:\n\(app.debugDescription)"
        )
    }

    func testTheFilesRowOpensThePickerOnOneTap() {
        let app = attachApp()

        openAttachSheet(app, row: "files").tap()

        XCTAssertTrue(
            app.tabBars["DOC.browsingModeTabBar"].waitForExistence(timeout: 6),
            "one tap on Files opens the document picker, on its Recents tab. Tree:\n\(app.debugDescription)"
        )
    }

    func testTheCameraRowOpensTheCameraOnOneTap() {
        let app = attachApp()

        openAttachSheet(app, row: "camera").tap()

        XCTAssertTrue(
            app.buttons["PhotoCapture"].waitForExistence(timeout: 6),
            "one tap on Camera opens the camera. Tree:\n\(app.debugDescription)"
        )
    }
}
