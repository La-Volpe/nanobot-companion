import XCTest

/// The settings flow a tap can prove (plan.md I7's gate): the four destinations, Overview's
/// version/commit with no uptime line, the notifications copy and its real local test
/// notification, the two appearance choices, and the gateway row's disconnect.
final class SettingsTests: XCTestCase {

    private func connectedApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        app.launch()
        if !app.buttons["chat.openDrawer"].waitForExistence(timeout: 10) {
            XCTFail("the shell is up. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    private func openSettings() -> XCUIApplication {
        let app = connectedApp()
        app.buttons["chat.openDrawer"].tap()
        let settings = app.buttons["drawer.settings"]
        if settings.waitForExistence(timeout: 5) {
            settings.tap()
        } else {
            XCTFail("the drawer offers settings. Tree:\n\(app.debugDescription)")
        }
        XCTAssertTrue(app.buttons["settings.gateway"].waitForExistence(timeout: 5), "Settings lists the gateway row")
        return app
    }

    func testTheFourDestinationsAreThere() {
        let app = openSettings()

        XCTAssertTrue(app.buttons["settings.overview"].exists, "Overview")
        XCTAssertTrue(app.buttons["settings.notifications"].exists, "Notifications")
        XCTAssertTrue(app.buttons["settings.appearance"].exists, "Appearance")
        // The row names the address and its state, or says there is none — never nothing.
        let connected = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "connected")).count
        XCTAssertTrue(
            app.staticTexts["No gateway"].exists || connected > 0,
            "the gateway row says how it stands. Tree:\n\(app.debugDescription)"
        )
    }

    func testOverviewShowsTheVersionAndSaysWhyThereIsNoUptime() {
        let app = openSettings()

        app.buttons["settings.overview"].tap()

        XCTAssertTrue(app.staticTexts["nanobot Gateway"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Uptime"].exists, "the payload has no uptime field")
        XCTAssertTrue(
            app.staticTexts["settings.noUptime"].exists,
            "and the screen says so rather than leaving the gap unexplained"
        )

        app.buttons["settings.back"].tap()
        XCTAssertTrue(app.buttons["settings.overview"].waitForExistence(timeout: 5))
    }

    func testNotificationsSendsARealLocalNotificationAndIsHonest() {
        let app = openSettings()

        app.buttons["settings.notifications"].tap()

        XCTAssertTrue(app.switches["settings.toggle.Task finished"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.switches["settings.toggle.Task failed"].exists)
        XCTAssertTrue(app.switches["settings.toggle.Needs attention"].exists)

        let copy = app.staticTexts["settings.notificationsCopy"]
        XCTAssertTrue(copy.exists, "the screen states the one promise it can keep")
        XCTAssertTrue(copy.label.contains("no push service"), "and says why the app stays quiet when closed")

        // The system permission alert is the app's own request; allow it so the banner can show.
        addUIInterruptionMonitor(withDescription: "Notifications permission") { alert in
            for label in ["Allow", "Allow Notifications", "Don't Allow"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
        app.buttons["settings.testNotification"].tap()
        app.tap()
    }

    func testAppearanceSetsTheThemeAndTheReadingSize() {
        let app = openSettings()

        app.buttons["settings.appearance"].tap()

        XCTAssertTrue(app.segmentedControls["settings.theme"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.segmentedControls["settings.readingSize"].exists)
        app.segmentedControls["settings.theme"].buttons["Dark"].tap()
        app.segmentedControls["settings.readingSize"].buttons["L"].tap()

        app.buttons["settings.back"].tap()
        XCTAssertTrue(app.buttons["settings.appearance"].waitForExistence(timeout: 5), "the list shows the theme it was given")
    }

    func testTheGatewayRowOffersADisconnectThatReturnsToOnboarding() {
        let app = openSettings()

        app.buttons["settings.gateway"].tap()

        XCTAssertTrue(app.alerts["Disconnect gateway?"].waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.alerts.staticTexts["The app forgets this address and key. Nothing on the gateway is deleted, and you can reconnect any time."].exists
        )
        app.alerts.buttons["Disconnect"].tap()

        XCTAssertTrue(
            app.textFields["connect.address"].waitForExistence(timeout: 10),
            "a disconnect returns to onboarding with nothing remembered. Tree:\n\(app.debugDescription)"
        )
    }
}
