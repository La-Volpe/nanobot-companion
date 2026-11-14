import XCTest

/// G2's second instrument: each of the eight connection failures is reachable from the app's
/// own debug menu, and shows the taxonomy's copy on the chat surface. The menu is DEBUG-only;
/// what it injects is the app's real banner and dot.
final class StateInjectionTests: XCTestCase {

    private func chatApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        app.launch()
        if !app.buttons["chat.debug"].waitForExistence(timeout: 10) {
            XCTFail("the debug menu is reachable from the chat. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    private func openDebug(_ app: XCUIApplication) {
        app.buttons["chat.debug"].tap()
        XCTAssertTrue(app.navigationBars["Debug states"].waitForExistence(timeout: 5))
    }

    /// The menu is a `List` with the eight failures above the chat states, so a state row can be
    /// below the fold; a person scrolls to it, and so does this.
    @discardableResult
    private func scrollTo(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        let element = app.buttons[identifier]
        var attempts = 0
        while !element.exists && attempts < 6 {
            app.swipeUp()
            attempts += 1
        }
        return element
    }

    /// One test per failure would be eight simulator launches; one walk through the menu proves
    /// the same thing and is what a person does by hand.
    func testEveryConnectionFailureIsReachableWithItsCopy() {
        let app = chatApp()
        let expected: [(identifier: String, copy: String, retryable: Bool)] = [
            ("authFailed", "That key was refused. Check the gateway's key and try again.", false),
            ("tlsRejected", "This gateway's certificate is not trusted. If it is your own, allow it in this gateway's settings.", false),
            ("unreachable", "No gateway at that address. Check the address and that the gateway is running.", true),
            ("timeout", "The gateway did not answer in time. Try again.", true),
            ("protocolTooNew", "This gateway speaks a newer protocol than this app. Update the app.", false),
            ("missingCapability", "This gateway does not offer that feature. The rest of the chat still works.", false),
            ("missingField", "The gateway's answer was incomplete. Check its version.", false),
            ("serverError", "The gateway refused the request. Try again in a moment.", true),
        ]

        var reached: [String] = []
        for failure in expected {
            openDebug(app)
            let row = app.buttons["debug.failure.\(failure.identifier)"]
            XCTAssertTrue(row.waitForExistence(timeout: 5), "\(failure.identifier) is in the menu. Tree:\n\(app.debugDescription)")
            row.tap()

            let banner = app.staticTexts["states.banner"]
            XCTAssertTrue(banner.waitForExistence(timeout: 5), "\(failure.identifier) raises the banner. Tree:\n\(app.debugDescription)")
            XCTAssertEqual(banner.label, failure.copy, "\(failure.identifier) shows the taxonomy's own sentence")
            XCTAssertTrue(app.otherElements["states.dot"].exists || app.images["states.dot"].exists || app.staticTexts["states.dot"].exists,
                          "the dot moves with it")
            reached.append(failure.identifier)
        }

        XCTAssertEqual(reached.count, 8, "all eight, one after the other")
    }

    func testTheDebugMenuAlsoInjectsTheChatStates() {
        let app = chatApp()

        openDebug(app)
        scrollTo(app, "debug.state.compaction").tap()
        XCTAssertTrue(app.staticTexts["Context compacted to keep the conversation going."].waitForExistence(timeout: 5),
                      "a compaction is one line in the thread. Tree:\n\(app.debugDescription)")

        openDebug(app)
        scrollTo(app, "debug.state.recovery").tap()
        XCTAssertTrue(app.buttons["states.action.continue"].waitForExistence(timeout: 5), "recovery offers Continue")
        XCTAssertTrue(app.buttons["states.action.dismiss"].exists, "and Dismiss")

        openDebug(app)
        scrollTo(app, "debug.state.subagent").tap()
        XCTAssertTrue(app.staticTexts["Subtask: audit attachment limits"].waitForExistence(timeout: 5), "the dock names the task")

        openDebug(app)
        scrollTo(app, "debug.state.queued").tap()
        XCTAssertTrue(app.staticTexts["composer.queue"].waitForExistence(timeout: 5), "the queued prompt has its chip")

        openDebug(app)
        scrollTo(app, "debug.state.notSent").tap()
        XCTAssertTrue(app.staticTexts["Not sent"].waitForExistence(timeout: 5), "a failed delivery keeps the words")

        openDebug(app)
        scrollTo(app, "debug.state.sandbox").tap()
        XCTAssertTrue(app.staticTexts["Shell tools are paused: the sandbox launcher is not installed."].waitForExistence(timeout: 5),
                      "the sandbox warning names its cause")

        openDebug(app)
        scrollTo(app, "debug.state.offline").tap()
        XCTAssertEqual(app.staticTexts["states.banner"].label, "Disconnected. Sends are held.")

        openDebug(app)
        scrollTo(app, "debug.state.reconnecting").tap()
        XCTAssertEqual(app.staticTexts["states.banner"].label, "Reconnecting…")

        openDebug(app)
        scrollTo(app, "debug.clear").tap()
        XCTAssertFalse(app.staticTexts["states.banner"].waitForExistence(timeout: 2), "Clear every injected state empties the surface")
    }
}
