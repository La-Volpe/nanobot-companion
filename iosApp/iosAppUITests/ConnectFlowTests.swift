import XCTest

/// The onboarding flow a tap can prove (plan.md I1's gate). The app launches against a
/// scripted gateway (DEBUG only, `NANOBOT_SCENARIO`), so the run needs no network, no key and
/// no Keychain — and the eight error states are reachable exactly the way the debug menu will
/// reach them.
final class ConnectFlowTests: XCTestCase {

    private func launch(_ scenario: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = scenario
        app.launch()
        return app
    }

    private func fillIn(_ app: XCUIApplication, address: String = "https://home.lan:8443", key: String = "secret") {
        let field = app.textFields["connect.address"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "onboarding is the first screen")
        field.tap()
        field.typeText(address)
        app.secureTextFields["connect.key"].tap()
        app.secureTextFields["connect.key"].typeText(key)
    }

    func testConnectReachesTheConnectedShell() {
        let app = launch("connected")
        fillIn(app)

        app.buttons["connect.submit"].tap()

        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 10), "a successful connect leaves onboarding for the chat")
    }

    func testARefusedKeyShowsTheSecretCopy() {
        let app = launch("authFailed")
        fillIn(app)

        app.buttons["connect.submit"].tap()

        let error = app.staticTexts["connect.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertTrue(error.label.contains("key was refused"), "the copy names the key: \(error.label)")
    }

    /// The switch is the user's decision, and the same gateway answers differently once it is on.
    func testTheUnverifiedCertificateSwitchIsWhatMakesASelfSignedGatewayWork() {
        let app = launch("selfSigned")
        fillIn(app)

        app.buttons["connect.submit"].tap()
        let error = app.staticTexts["connect.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertTrue(error.label.contains("certificate is not trusted"))

        let toggle = app.switches["connect.allowUnverified"].firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.switches.firstMatch.tap()
        XCTAssertEqual(toggle.value as? String, "1", "the switch is on because the user turned it on")

        app.buttons["connect.submit"].tap()
        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 10), "the same gateway connects once trusted")
    }

    func testAnAddressWithoutASchemeIsRefusedLocally() {
        let app = launch("connected")
        fillIn(app, address: "192.168.1.10:8787")

        app.buttons["connect.submit"].tap()

        let error = app.staticTexts["connect.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertTrue(error.label.contains("including http:// or https://"))
    }
}
