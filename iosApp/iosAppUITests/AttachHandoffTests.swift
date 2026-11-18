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
            app.navigationBars["Photos"].waitForExistence(timeout: 20),
            "one tap on Photo or video opens the picker; nothing else is tapped. Tree:\n\(app.debugDescription)"
        )
    }

    func testTheFilesRowOpensThePickerOnOneTap() {
        let app = attachApp()

        openAttachSheet(app, row: "files").tap()

        XCTAssertTrue(
            app.tabBars["DOC.browsingModeTabBar"].waitForExistence(timeout: 20),
            "one tap on Files opens the document picker, on its Recents tab. Tree:\n\(app.debugDescription)"
        )
    }

    func testTheCameraRowOpensTheCameraOnOneTap() throws {
        let app = attachApp()

        openAttachSheet(app, row: "camera").tap()

        if app.buttons["PhotoCapture"].waitForExistence(timeout: 6) { return }

        // A hosted macOS simulator has no camera, and the app refuses with a sentence rather than a black
        // screen: that sentence is the observable then, and a machine without the hardware cannot prove the
        // camera opened. `CameraAccess.refusal` is what decides it, and this is the same shape as
        // `KeyboardTests` skipping when the host draws no software keyboard.
        let said = app.staticTexts.allElementsBoundByIndex.contains { element in
            element.label.localizedCaseInsensitiveContains("camera")
        }
        if said {
            throw XCTSkip("this simulator has no camera; the row correctly says so instead of opening one")
        }
        XCTFail("one tap on Camera neither opened the camera nor said why. Tree:\n\(app.debugDescription)")
    }
}
