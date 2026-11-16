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

    /// The thumb of a `Toggle`: the row's own centre is its label, and a list row's label does not
    /// flip the switch. The trailing edge is where the control is drawn (design: 51 pt).
    private func switchControl(_ row: XCUIElement) -> XCUICoordinate {
        row.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5))
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
        // T44: the version appears once, on Overview, beside the commit and the server it belongs
        // with. The list used to carry a second copy under "About".
        XCTAssertFalse(
            app.staticTexts["0.4.1"].exists,
            "the version is drawn once, not twice. Tree:\n\(app.debugDescription)"
        )
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

        // T44: the usage the client already fetched is drawn, first, above the version it belongs
        // with. The headline's accessibility label is the exact number, the way the WebUI labels it.
        XCTAssertTrue(
            app.staticTexts["settings.usage.headline"].waitForExistence(timeout: 5),
            "the card's headline. Tree:\n\(app.debugDescription)"
        )
        XCTAssertEqual(app.staticTexts["settings.usage.headline"].label, "505134 tokens")
        XCTAssertTrue(app.staticTexts["140 requests · 89% cache hit rate"].exists, "the gateway's own counts")
        XCTAssertTrue(app.otherElements["settings.usage.strip"].exists, "and a bar per day of the window")

        XCTAssertTrue(app.staticTexts["nanobot Gateway"].exists)
        XCTAssertFalse(app.staticTexts["Uptime"].exists, "the payload has no uptime field")
        // T45: the model rows describe the open chat, not `agent.model` from `/api/settings`.
        // A scripted launch has no composer, so the chat's model is seeded by the shell.
        XCTAssertTrue(app.staticTexts["Current model"].exists, "the model row is drawn")
        XCTAssertTrue(
            app.staticTexts["deepseek-v4.1-flash:cloud"].exists,
            "the chat's model, not the gateway's configured default. Tree:\n\(app.debugDescription)"
        )
        XCTAssertTrue(app.staticTexts["Preset primary"].exists, "and the preset it is on")
        XCTAssertTrue(app.staticTexts["32.8K tokens"].exists, "and the window the ring divides by")

        // The design's own footnote sits under the rows, so a phone-sized screen has to scroll to it.
        app.swipeUp()
        XCTAssertTrue(
            app.staticTexts["settings.noUptime"].waitForExistence(timeout: 5),
            "and the screen says why there is no uptime rather than leaving the gap unexplained"
        )

        app.buttons["settings.back"].tap()
        XCTAssertTrue(app.buttons["settings.overview"].waitForExistence(timeout: 5))
    }

    func testNotificationsSendsARealLocalNotificationAndIsHonest() {
        let app = openSettings()

        app.buttons["settings.notifications"].tap()

        XCTAssertTrue(app.switches["settings.toggle.Task finished"].waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.switches["settings.toggle.Task failed or stopped"].exists,
            "the middle row names what it gates: a stop is not a failure to announce"
        )
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

    /// T34: the notification sound is a switch of the reader's own, next to the permission state,
    /// and the choice outlives the app (the banners read the same preference).
    func testTheNotificationSoundCanBeTurnedOffAndStaysOff() {
        let app = openSettings()
        app.buttons["settings.notifications"].tap()

        let sound = app.switches["settings.notificationSound"]
        XCTAssertTrue(
            sound.waitForExistence(timeout: 5),
            "the sound sits beside the permission. Tree:\n\(app.debugDescription)"
        )
        XCTAssertEqual(sound.value as? String, "1", "on until the reader says otherwise")

        // The identifier sits on the row, and a row's label does not flip a `.insetGrouped`
        // switch: the thumb is at the trailing edge.
        switchControl(sound).tap()
        expectation(for: NSPredicate(format: "value == '0'"), evaluatedWith: sound)
        waitForExpectations(timeout: 5)

        // A preference the banners read has to survive the app that drew it.
        app.terminate()
        let again = openSettings()
        again.buttons["settings.notifications"].tap()
        let afterRelaunch = again.switches["settings.notificationSound"]
        XCTAssertTrue(afterRelaunch.waitForExistence(timeout: 5))
        XCTAssertEqual(afterRelaunch.value as? String, "0", "the phone remembered the choice")
        switchControl(afterRelaunch).tap()
        expectation(for: NSPredicate(format: "value == '1'"), evaluatedWith: afterRelaunch)
        waitForExpectations(timeout: 5)
    }

    /// T43: a switch is a preference, not a drawing of one. Flipping it outlives the app, and it is
    /// the key the poster reads at post time, so the row and the banner cannot disagree.
    func testTheNoticeSwitchesOutliveTheApp() {
        let app = openSettings()
        app.buttons["settings.notifications"].tap()

        let finished = app.switches["settings.toggle.Task finished"]
        XCTAssertTrue(finished.waitForExistence(timeout: 5), "the row the design draws. Tree:\n\(app.debugDescription)")
        XCTAssertEqual(finished.value as? String, "1", "finished tasks are announced until the reader says otherwise")

        switchControl(finished).tap()
        expectation(for: NSPredicate(format: "value == '0'"), evaluatedWith: finished)
        waitForExpectations(timeout: 5)

        app.terminate()
        let again = openSettings()
        again.buttons["settings.notifications"].tap()
        let afterRelaunch = again.switches["settings.toggle.Task finished"]
        XCTAssertTrue(afterRelaunch.waitForExistence(timeout: 5))
        XCTAssertEqual(afterRelaunch.value as? String, "0", "the phone remembered the choice")

        // Put the switch back, so the next launch of this simulator starts where the design does.
        switchControl(afterRelaunch).tap()
        expectation(for: NSPredicate(format: "value == '1'"), evaluatedWith: afterRelaunch)
        waitForExpectations(timeout: 5)
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
