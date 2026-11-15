import XCTest

/// Putting the keyboard away (owner report): with the composer focused there was no way to
/// dismiss it, and the composer sat under it. An iPhone user flicks the transcript first, so that
/// is the first test; a tap is the second. Both end by asking the accessibility tree whether the
/// keyboard is still there, and where the composer's field sits, rather than trusting a picture.
final class KeyboardTests: XCTestCase {

    /// The simulator keeps whatever orientation the last test left it in, and a landscape chat has
    /// almost no transcript above the keyboard: a leak here reads as a different app, not a
    /// different test.
    override func setUpWithError() throws {
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDownWithError() throws {
        XCUIDevice.shared.orientation = .portrait
    }

    private func chatApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        app.launch()
        if !app.textFields["chat.composer"].waitForExistence(timeout: 10) {
            XCTFail("the chat is the connected screen. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    /// Typing is what makes the software keyboard real: a focused-but-empty field and a keyboard
    /// that is up look the same in a tree, and only one of them has a keyboard element.
    private func focusComposer(_ app: XCUIApplication) -> CGRect {
        let field = app.textFields["chat.composer"]
        field.tap()
        XCTAssertTrue(
            app.keyboards.element.waitForExistence(timeout: 5),
            "tapping the field raises the software keyboard. Tree:\n\(app.debugDescription)"
        )
        return field.frame
    }

    /// A tap in the conversation, away from the composer and the navigation bar. A coordinate
    /// rather than a label: the thread's answer text flips between `StaticText` and `Other` from
    /// one tree read to the next (XCUITest's legacy-attribute quirk), so a test that waited on the
    /// type would be waiting on the framework, and the row a tap lands on does not matter — a
    /// control still does its own job, which is the point.
    /// The thread's scroll view. Not `firstMatch`: the composer's field is a vertical-axis
    /// `TextField`, which is a `UITextView`, which is a scroll view too — and it is the one
    /// `firstMatch` hands back once the keyboard is up. The tallest match is the transcript.
    private func threadScroll(_ app: XCUIApplication) -> XCUIElement {
        app.scrollViews.allElementsBoundByIndex.max { $0.frame.height < $1.frame.height }
            ?? app.scrollViews.firstMatch
    }

    /// A point inside the thread, as a fraction of the window rather than a computed offset.
    ///
    /// Two earlier attempts read absolute frames and both landed on the keyboard (y=437 with the
    /// keyboard starting at y=238): after a rotation XCUITest keeps answering element queries with
    /// pre-rotation geometry for a while, so a frame-derived point is a point on the wrong screen.
    /// A normalised offset rides whatever frame the query hands back — on a stale 874-tall window
    /// it is y=218, on a fresh 402-tall landscape window y=100 — and both are transcript, because
    /// the keyboard owns the bottom of the screen in either orientation and 0.25 is never in it.
    private func transcriptPoint(_ app: XCUIApplication) -> XCUICoordinate {
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
    }

    private func tapTranscript(_ app: XCUIApplication) {
        let point = transcriptPoint(app)
        if app.keyboards.count > 0 {
            XCTAssertLessThan(
                point.screenPoint.y,
                app.keyboards.element.frame.minY,
                "the tap lands on the conversation, not on the keyboard (point \(point.screenPoint), "
                    + "keyboard \(app.keyboards.element.frame), window \(app.windows.firstMatch.frame))"
            )
        }
        point.tap()
    }

    /// The claim the report makes: the keyboard left. Not a flag, and not a screenshot.
    private func assertKeyboardIsGone(_ app: XCUIApplication, _ what: String) {
        XCTAssertTrue(
            app.keyboards.firstMatch.waitForNonExistence(timeout: 5),
            "\(what) leaves no keyboard. Tree:\n\(app.debugDescription)"
        )
        XCTAssertEqual(app.keyboards.count, 0, "\(what) leaves no keyboard")
        // The numbers, in the log, for the report: a tree with no keyboard and a composer that
        // came back down is the claim, not the absence of a failure.
        print("KB \(what): keyboards=\(app.keyboards.count) composer=\(app.textFields["chat.composer"].frame)")
    }

    func testTheComposerRidesAboveTheKeyboard() {
        let app = chatApp()
        let away = app.textFields["chat.composer"].frame
        let up = focusComposer(app)

        let keyboard = app.keyboards.element.frame
        XCTAssertLessThanOrEqual(
            up.maxY,
            keyboard.minY + 1,
            "the composer sits above the keyboard, not under it (composer \(up), keyboard \(keyboard))"
        )
        XCTAssertLessThan(up.minY, away.minY, "and it was pushed up from where it rests")
        XCTAssertEqual(app.keyboards.count, 1)
    }

    func testTappingTheTranscriptPutsTheKeyboardAwayAndTheComposerReturns() {
        let app = chatApp()
        let atRest = app.textFields["chat.composer"].frame
        let raised = focusComposer(app)
        XCTAssertGreaterThan(raised.minY, 0)

        tapTranscript(app)

        assertKeyboardIsGone(app, "a tap on the transcript")
        let settled = app.textFields["chat.composer"].frame
        XCTAssertEqual(settled.minY, atRest.minY, accuracy: 1, "the composer drops back to where it rests")
    }

    func testFlickingTheTranscriptPutsTheKeyboardAway() {
        let app = chatApp()
        _ = focusComposer(app)

        // A downward drag from the middle of the conversation: the flick an iPhone user tries
        // first, and what `.scrollDismissesKeyboard(.interactively)` is for.
        let start = transcriptPoint(app)
        let end = start.withOffset(CGVector(dx: 0, dy: 420))
        start.press(forDuration: 0.1, thenDragTo: end, withVelocity: .default, thenHoldForDuration: 0.1)

        assertKeyboardIsGone(app, "a downward flick on the transcript")
    }

    func testAToolRowsDisclosureStillOpensAndTheTapStillDismisses() {
        let app = chatApp()
        _ = focusComposer(app)

        // The cluster starts closed, so its head is the first control a tap on the transcript
        // could swallow; the rows behind it are the second.
        let head = app.buttons["activity.head"]
        XCTAssertTrue(head.waitForExistence(timeout: 5), "the scripted history's cluster")
        head.tap()
        let reasoning = app.buttons["activity.reasoning"]
        XCTAssertTrue(
            reasoning.waitForExistence(timeout: 5),
            "the cluster's own disclosure still opens — the dismissal must not swallow it. Tree:\n\(app.debugDescription)"
        )
        assertKeyboardIsGone(app, "a tap on a tool row")

        reasoning.tap()
        XCTAssertTrue(
            app.staticTexts["activity.reasoning.details"].waitForExistence(timeout: 5),
            "and a row's fold still opens"
        )

        let tool = app.buttons["activity.tool"]
        XCTAssertTrue(tool.waitForExistence(timeout: 5), "and the tool row")
        tool.tap()
        XCTAssertTrue(
            app.staticTexts["Arguments"].waitForExistence(timeout: 5),
            "the tool row's own disclosure still opens. Tree:\n\(app.debugDescription)"
        )
    }

    /// Rotation is a layout question, not a dismissal one: this case asserts the composer rides
    /// above the keyboard in landscape and comes home to its resting frame afterwards. It does not
    /// tap in landscape, because XCUITest resolves `screenPoint` through pre-rotation geometry
    /// there — measured: a coordinate 0.25 down a window reported as (0,0,874,402) resolved to
    /// y=437 with the keyboard starting at y=238, so the "tap the transcript" step landed on a
    /// keyboard key and every later assertion in the case failed behind it. Dismissal by tap has
    /// its own case in the orientation where the framework's geometry is trustworthy.
    func testRotationKeepsTheComposerAboveTheKeyboardAndItReturnsAfterDismissal() {
        let app = chatApp()
        let portraitRest = app.textFields["chat.composer"].frame
        _ = focusComposer(app)

        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }

        let field = app.textFields["chat.composer"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the composer survived the rotation")
        Thread.sleep(forTimeInterval: 1.5)

        XCTAssertEqual(app.keyboards.count, 1, "the keyboard survived the rotation")
        XCTAssertLessThanOrEqual(
            field.frame.maxY,
            app.keyboards.element.frame.minY + 1,
            "and the composer is still above it (composer \(field.frame), keyboard \(app.keyboards.element.frame))"
        )
        XCTAssertGreaterThan(
            field.frame.height,
            0,
            "the composer is still on screen in landscape at \(field.frame)"
        )

        XCUIDevice.shared.orientation = .portrait
        Thread.sleep(forTimeInterval: 1)

        XCTAssertEqual(app.keyboards.count, 1, "the keyboard is still up unless it is dismissed")

        // Back in portrait, the same tap the other cases prove: the conversation clears it, and the
        // composer lands where it started.
        tapTranscript(app)
        assertKeyboardIsGone(app, "a tap after rotation")
        XCTAssertEqual(
            app.textFields["chat.composer"].frame.minY,
            portraitRest.minY,
            accuracy: 1,
            "and back in portrait it rests where it started"
        )
    }

    func testSendStillWorksWithTheKeyboardUpAndTheFocusIsNotTakenBack() {
        let app = chatApp()
        let atRest = app.textFields["chat.composer"].frame
        _ = focusComposer(app)

        let field = app.textFields["chat.composer"]
        field.typeText("sent while the keyboard is up")
        let send = app.buttons["chat.send"]
        XCTAssertTrue(send.waitForExistence(timeout: 5), "typing turns the button into Send")
        send.tap()

        XCTAssertTrue(
            app.staticTexts["sent while the keyboard is up"].waitForExistence(timeout: 5),
            "the send lands with the keyboard up"
        )

        // Dismiss, then let the stream run: a redraw that took the focus back would raise the
        // keyboard again, which is the second half of the report.
        tapTranscript(app)
        assertKeyboardIsGone(app, "a tap after a send")
        Thread.sleep(forTimeInterval: 2)
        XCTAssertEqual(app.keyboards.count, 0, "the streaming answer did not take the focus back")
        XCTAssertEqual(
            field.frame.minY,
            atRest.minY,
            accuracy: 1,
            "and the composer stays where the dismissal left it"
        )
    }
}
