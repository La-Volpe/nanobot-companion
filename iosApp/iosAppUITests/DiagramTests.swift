import XCTest

/// A mermaid fence in the chat: the picture, its source, and the toggle between them (owner's
/// decision, 2026-11-15). The launch is the markdown fixture (`NANOBOT_SCENARIO=markdown`, DEBUG
/// only), so the parse, the streaming split and the renderer are the real ones with no model in
/// the loop.
final class DiagramTests: XCTestCase {

    private func chatApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "markdown"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_ADDRESS"] = "http://127.0.0.1:18765"
        app.launch()
        if !app.textFields["chat.composer"].waitForExistence(timeout: 10) {
            XCTFail("the chat is the connected screen. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    /// The thread is taller than the phone and the answer arrives in chunks, so the diagram is
    /// only in the tree once both are done: the turn's caret goes, then the row is scrolled to.
    private func scrollTo(_ app: XCUIApplication, _ element: XCUIElement, swipes: Int = 14) {
        for _ in 0..<swipes {
            if element.exists { return }
            app.swipeUp()
        }
    }

    private func diagram(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    private func save(_ app: XCUIApplication, _ name: String) {
        let data = app.screenshot().pngRepresentation
        try? data.write(to: URL(fileURLWithPath: "/tmp/\(name).png"))
    }

    func testADiagramEndsOnItsPreviewAndFlipsToItsSource() {
        let app = chatApp()
        let picture = diagram(app, "diagram.preview")
        scrollTo(app, picture)

        let source = app.buttons.matching(identifier: "diagram.view.source").firstMatch
        let preview = app.buttons.matching(identifier: "diagram.view.preview").firstMatch
        XCTAssertTrue(picture.waitForExistence(timeout: 10), "the diagram is drawn. Tree:\n\(app.debugDescription)")
        XCTAssertTrue(preview.exists, "the toggle is in the block's header")
        // The turn has ended, so the block opens on the picture the core chose for it.
        XCTAssertTrue(preview.isSelected, "preview is the tab in force")
        save(app, "ios-diagram-preview")

        source.tap()
        XCTAssertTrue(app.staticTexts["graph TD"].waitForExistence(timeout: 5), "the fence's own text is on screen")
        XCTAssertFalse(picture.exists, "one half at a time")
        save(app, "ios-diagram-source")

        preview.tap()
        XCTAssertTrue(picture.waitForExistence(timeout: 10), "and back to the picture")
    }

    func testADiagramThatCannotBeDrawnFallsBackToItsSource() {
        let app = chatApp()
        let failed = app.staticTexts["This diagram could not be drawn, so its source is shown."]
        scrollTo(app, failed)

        XCTAssertTrue(failed.waitForExistence(timeout: 10), "the broken fence says so. Tree:\n\(app.debugDescription)")
        // Both fences start with the same first line, so the source being on screen is what the
        // broken block shows; no second preview half exists for it.
        XCTAssertTrue(app.staticTexts["graph TD"].firstMatch.exists, "and shows the source it could not draw")
        // The sentence only appears in the source half, which is the whole fallback: a failed
        // diagram is not a box with nothing in it.
        save(app, "ios-diagram-failed")
    }
}
