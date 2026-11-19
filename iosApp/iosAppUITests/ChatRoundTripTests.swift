import XCTest

/// The chat flow a tap can prove: the history loads, a send appears at
/// once, the answer streams in, and Stop is what the button becomes while a turn runs.
/// The scripted chat streams on the same timeline as a real one (DEBUG only).
final class ChatRoundTripTests: XCTestCase {

    private func chatApp(turnSeconds: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        if let turnSeconds { app.launchEnvironment["NANOBOT_SCRIPTED_TURN_SECONDS"] = turnSeconds }
        app.launch()
        // The composer's field is what proves the shell: with an empty field the primary button
        // is the microphone, so waiting for "chat.send" would wait for a state the screen is
        // not in yet.
        if !app.textFields["chat.composer"].waitForExistence(timeout: 10) {
            XCTFail("the chat is the connected screen. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    func testHistoryLoadsThroughTheFacade() {
        let app = chatApp()

        XCTAssertTrue(app.staticTexts["earlier question"].waitForExistence(timeout: 10), "the attach replay fills the thread")
        XCTAssertTrue(app.staticTexts["an earlier answer"].exists)
    }

    /// A send, then the answer. Both are state that stays: the bubble is a row in the thread and
    /// the streamed answer stays beside the history one, so the polls below cannot miss a state
    /// that has already gone.
    func testASendAppearsThenStreamsTheAnswer() {
        let app = chatApp()
        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("hello from the test")
        app.buttons["chat.send"].tap()

        XCTAssertTrue(app.staticTexts["hello from the test"].waitForExistence(timeout: 5), "the bubble is on screen before the answer")

        // The streamed answer is a second answer row next to the history one; the growth of a
        // single row is `ChatViewModelTests.streamingAppearsIncrementally`'s job. The rows outlive
        // the turn, so a runner that is slow to answer this question is answered anyway.
        let answers = app.staticTexts.matching(identifier: "chat.answer")
        let deadline = Date().addingTimeInterval(20)
        while answers.count < 2 && Date() < deadline {
            usleep(200_000)
        }
        XCTAssertGreaterThanOrEqual(answers.count, 2, "the streamed answer lands in the thread")
    }

    /// The button rule for a prompt the reader sent, with the turn held open for the length of the
    /// test. Left to its default the scripted turn runs for six seconds (a pending prompt, then
    /// four chunks 1.4 s apart), and a loaded runner spends most of that waiting for the bubble:
    /// hosted run 44412530371 read the button 7.8 s after the send, found the turn already over and
    /// failed with "the turn is running" while the tree held the whole answer and the microphone.
    /// A constant is what raced, not a control (`NANOBOT_SCRIPTED_TURN_SECONDS`, AGENTS gotcha 21).
    func testTheButtonIsStopWhileASentTurnRuns() {
        let app = chatApp(turnSeconds: "30")
        let composer = app.textFields["chat.composer"]
        composer.typeSettling("hello from the test")
        app.buttons["chat.send"].tap()

        XCTAssertTrue(app.staticTexts["hello from the test"].waitForExistence(timeout: 15), "the bubble is on screen while the turn runs")
        XCTAssertTrue(app.buttons["chat.stop"].waitForExistence(timeout: 15), "the turn is running")
    }

    func testTheButtonIsStopWhileATurnRunsAndSendWhenSomethingIsTyped() {
        // Held open for the length of the test. The scripted reply otherwise finishes between the
        // first send and the second tap, and the run then fails with "Neither element nor any
        // descendant has keyboard focus" (hosted run 44393023565) — a timer in the instrument, not
        // a missing control (`NANOBOT_SCRIPTED_TURN_SECONDS`, AGENTS gotcha 21).
        //
        // This is also where the button rule for a *sent* prompt is proven end to end: a turn
        // that is deliberately still running, Stop while it runs, and Send as soon as the field
        // holds text again.
        let app = chatApp(turnSeconds: "30")
        let composer = app.textFields["chat.composer"]
        composer.typeSettling("start a turn")
        app.buttons["chat.send"].tap()

        XCTAssertTrue(app.buttons["chat.stop"].waitForExistence(timeout: 15), "an empty field during a turn means Stop")

        composer.typeSettling("and this is a steer")
        XCTAssertTrue(app.buttons["chat.send"].waitForExistence(timeout: 5), "typing turns the button back into Send")
    }
}
