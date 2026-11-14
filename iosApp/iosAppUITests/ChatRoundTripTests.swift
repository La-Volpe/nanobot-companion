import XCTest

/// The chat flow a tap can prove (plan.md I3's gate): the history loads, a send appears at
/// once, the answer streams in, and Stop is what the button becomes while a turn runs.
/// The scripted chat streams on the same timeline as a real one (DEBUG only).
final class ChatRoundTripTests: XCTestCase {

    private func chatApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        app.launch()
        if !app.buttons["chat.send"].waitForExistence(timeout: 10) {
            XCTFail("the chat is the connected screen. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    func testHistoryLoadsThroughTheFacade() {
        let app = chatApp()

        XCTAssertTrue(app.staticTexts["earlier question"].waitForExistence(timeout: 10), "the attach replay fills the thread")
        XCTAssertTrue(app.staticTexts["an earlier answer"].exists)
    }

    func testASendAppearsThenStreamsThenStops() {
        let app = chatApp()
        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("hello from the test")
        app.buttons["chat.send"].tap()

        XCTAssertTrue(app.staticTexts["hello from the test"].waitForExistence(timeout: 5), "the bubble is on screen before the answer")

        XCTAssertTrue(app.buttons["chat.stop"].waitForExistence(timeout: 5), "the turn is running")

        // The streamed answer is a second answer row next to the history one; the growth of a
        // single row is `ChatViewModelTests.streamingAppearsIncrementally`'s job.
        let answers = app.staticTexts.matching(identifier: "chat.answer")
        let deadline = Date().addingTimeInterval(8)
        while answers.count < 2 && Date() < deadline {
            usleep(200_000)
        }
        XCTAssertGreaterThanOrEqual(answers.count, 2, "the streamed answer lands in the thread")
    }

    func testTheButtonIsStopWhileATurnRunsAndSendWhenSomethingIsTyped() {
        let app = chatApp()
        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("start a turn")
        app.buttons["chat.send"].tap()

        XCTAssertTrue(app.buttons["chat.stop"].waitForExistence(timeout: 5), "an empty field during a turn means Stop")

        composer.tap()
        composer.typeText("and this is a steer")
        XCTAssertTrue(app.buttons["chat.send"].waitForExistence(timeout: 5), "typing turns the button back into Send")
    }
}
