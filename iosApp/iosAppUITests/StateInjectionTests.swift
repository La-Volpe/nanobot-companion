import XCTest

/// The second instrument: each of the eight connection failures is reachable from the app's
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
        XCTAssertTrue(app.navigationBars["Debug states"].waitForExistence(timeout: 15))
    }

    /// Inject one state and wait for the sheet to be gone. The surface underneath only updates
    /// once the dismissal finishes, and on a loaded hosted runner that plus the re-render
    /// outlasts a five-second poll, which is what made this walk fail there and pass here.
    private func inject(_ app: XCUIApplication, _ identifier: String) {
        openDebug(app)
        scrollTo(app, identifier).tap()
        XCTAssertTrue(
            app.navigationBars["Debug states"].waitForNonExistence(timeout: 15),
            "the sheet closes before the surface is read"
        )
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

        inject(app, "debug.state.compaction")
        XCTAssertTrue(app.staticTexts["Context compacted to keep the conversation going."].waitForExistence(timeout: 15),
                      "a compaction is one line in the thread. Tree:\n\(app.debugDescription)")

        inject(app, "debug.state.recovery")
        XCTAssertTrue(app.buttons["states.action.continue"].waitForExistence(timeout: 15), "recovery offers Continue")
        XCTAssertTrue(app.buttons["states.action.dismiss"].exists, "and Dismiss")

        inject(app, "debug.state.subagent")
        XCTAssertTrue(app.staticTexts["Subtask: audit attachment limits"].waitForExistence(timeout: 15), "the dock names the task")

        inject(app, "debug.state.queued")
        XCTAssertTrue(app.staticTexts["composer.queue"].waitForExistence(timeout: 15), "the queued prompt has its chip")

        inject(app, "debug.state.notSent")
        XCTAssertTrue(app.staticTexts["Not sent"].waitForExistence(timeout: 15), "a failed delivery keeps the words")

        inject(app, "debug.state.sandbox")
        XCTAssertTrue(app.staticTexts["Shell tools are paused: the sandbox launcher is not installed."].waitForExistence(timeout: 15),
                      "the sandbox warning names its cause")

        inject(app, "debug.state.offline")
        let offline = app.staticTexts["states.banner"]
        XCTAssertTrue(offline.waitForExistence(timeout: 15), "the offline banner is up. Tree:\n\(app.debugDescription)")
        XCTAssertEqual(offline.label, "Disconnected. Sends are held.")

        inject(app, "debug.state.reconnecting")
        let reconnecting = app.staticTexts["states.banner"]
        XCTAssertTrue(reconnecting.waitForExistence(timeout: 15), "the reconnecting banner is up")
        XCTAssertEqual(reconnecting.label, "Reconnecting…")

        inject(app, "debug.clear")
        XCTAssertTrue(app.staticTexts["states.banner"].waitForNonExistence(timeout: 15), "Clear every injected state empties the surface")
    }
}
