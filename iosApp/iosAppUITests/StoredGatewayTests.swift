import XCTest

/// The key a device already holds is the reason a second launch is not a second setup: connect
/// once by typing, then relaunch with nothing in the environment and expect the thread, not the
/// onboarding screen. Opt-in, because it needs a real gateway to accept the key.
///
///     TEST_RUNNER_NANOBOT_LIVE_URL=http://127.0.0.1:18765 \
///     TEST_RUNNER_NANOBOT_LIVE_SECRET=$(cat .dev/secret) xcodebuild test …
final class StoredGatewayTests: XCTestCase {

    private var url: String { ProcessInfo.processInfo.environment["NANOBOT_LIVE_URL"] ?? "" }
    private var secret: String { ProcessInfo.processInfo.environment["NANOBOT_LIVE_SECRET"] ?? "" }

    func testATypedGatewayReopensWithoutAskingAgain() throws {
        try XCTSkipIf(url.isEmpty || secret.isEmpty, "no live gateway: set NANOBOT_LIVE_URL and NANOBOT_LIVE_SECRET")

        let app = XCUIApplication()
        // No scenario, no live environment: this is the production composition root, so the
        // address and the key go through the Keychain like they would on a phone. The reset
        // flag empties the store first, so this really is a first launch.
        app.launchEnvironment["NANOBOT_FORGET"] = "1"
        app.launch()
        app.activate()

        let address = app.textFields["connect.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 10), "a fresh install starts on onboarding")
        address.tap()
        address.typeText(url)

        let key = app.secureTextFields["connect.key"]
        key.tap()
        key.typeText(secret)

        // The keyboard covers the button, so the sheet is scrolled first.
        app.swipeUp()
        app.buttons["connect.submit"].firstMatch.tap()

        let thread = app.buttons["chat.openDrawer"]
        XCTAssertTrue(thread.waitForExistence(timeout: 30), "the first connect never reached the thread")

        // The real test: a new process, nothing in the environment, no typing.
        app.terminate()
        app.launchEnvironment = [:]
        app.launch()
        app.activate()

        XCTAssertTrue(
            app.buttons["chat.openDrawer"].waitForExistence(timeout: 30),
            "the second launch asked for the gateway again instead of using the stored key"
        )
        XCTAssertFalse(app.textFields["connect.address"].exists, "onboarding came back")
    }

    override func tearDown() {
        // Leave nothing behind: the next test must see a fresh install.
        let app = XCUIApplication()
        if app.state == .runningForeground { app.terminate() }
    }
}
