import XCTest

/// Two launches from the same phone: the one `tools/run-on-device.sh` makes (the gateway arrives
/// in the launch environment), and the one the owner makes by tapping the icon. The second must
/// open the chat. Opt-in, because it needs a real gateway to accept the key:
///
///     TEST_RUNNER_NANOBOT_LIVE_URL=http://127.0.0.1:18765 \
///     TEST_RUNNER_NANOBOT_LIVE_SECRET=$(cat .dev/secret) xcodebuild test …
final class StoredLaunchTests: XCTestCase {

    private var url: String { ProcessInfo.processInfo.environment["NANOBOT_LIVE_URL"] ?? "" }
    private var secret: String { ProcessInfo.processInfo.environment["NANOBOT_LIVE_SECRET"] ?? "" }

    func testALaunchThatCarriesAGatewayLeavesOneForTheNextLaunch() throws {
        try XCTSkipIf(url.isEmpty || secret.isEmpty, "no live gateway: set NANOBOT_LIVE_URL and NANOBOT_LIVE_SECRET")

        let app = XCUIApplication()
        // The reset flag first, so this is a phone that has never held a key.
        app.launchEnvironment = [
            "NANOBOT_FORGET": "1",
            "NANOBOT_LIVE_URL": url,
            "NANOBOT_LIVE_SECRET": secret,
        ]
        app.launch()
        app.activate()

        XCTAssertTrue(
            app.textFields["chat.composer"].waitForExistence(timeout: 30),
            "a launch that already carries the address and the key still asked for them"
        )

        // The second launch: a new process, nothing in the environment, nobody typing.
        app.terminate()
        app.launchEnvironment = [:]
        app.launch()
        app.activate()

        XCTAssertTrue(
            app.textFields["chat.composer"].waitForExistence(timeout: 30),
            "the next launch opened on the connect screen instead of the gateway it stored"
        )
        XCTAssertFalse(app.textFields["connect.address"].exists, "onboarding came back")
    }
}
