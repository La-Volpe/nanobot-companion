import XCTest

/// The attachments and voice flow a tap can prove (plan.md I6's gate): the attach sheet offers
/// the design's three ways in with the real limits on it, a recording shows its meter and its
/// floor copy, and tap-to-dictate fills the field with what the gateway transcribed.
///
/// The system photo and file pickers cannot be driven by XCUITest, and a simulator has no
/// microphone: the scripted launch substitutes both (`ScriptedUploader`, `ScriptedRecorder`,
/// `ScriptedVoice`), so what is proven here is the app's own states — the bytes through the real
/// gateway are the live scripted session's job.
final class VoiceTests: XCTestCase {

    private func chatApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        app.launch()
        if !app.buttons["chat.voice"].waitForExistence(timeout: 10) {
            XCTFail("an empty field offers the microphone. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    func testTheAttachSheetCarriesTheRealLimits() {
        let app = chatApp()

        app.buttons["composer.attach"].tap()

        XCTAssertTrue(app.staticTexts["Attach"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["attach.photos"].exists, "Photo or video")
        XCTAssertTrue(app.buttons["attach.files"].exists, "Files")
        XCTAssertTrue(
            app.staticTexts["Up to 4 files, 6 MiB each, 1 video"].exists,
            "the sheet's second line is the gateway's own limit. Tree:\n\(app.debugDescription)"
        )
        XCTAssertTrue(app.buttons["attach.camera"].exists, "Camera is offered, and disabled when there is none")
    }

    /// A hold between the tap threshold and the gateway's floor is neither a tap nor audio
    /// worth sending, so it says so and sends nothing.
    func testAHoldUnderTheFloorSaysToHoldLonger() {
        let app = chatApp()

        app.buttons["chat.voice"].press(forDuration: 0.45)

        XCTAssertTrue(
            app.staticTexts["Hold a little longer to record voice."].waitForExistence(timeout: 5),
            "under the gateway's 650 ms floor nothing is sent. Tree:\n\(app.debugDescription)"
        )
        XCTAssertTrue(app.buttons["chat.voice"].exists, "and the button is a microphone again")
    }

    func testTappingTheMicrophoneDictatesAndFillsTheField() {
        let app = chatApp()

        // A short press arms tap-to-dictate: the recording keeps running and the meter shows it.
        app.buttons["chat.voice"].press(forDuration: 0.1)
        XCTAssertTrue(app.staticTexts["0:00"].waitForExistence(timeout: 5), "the meter shows the recording is live")

        // The next tap stops it; the transcript arrives in the field.
        app.buttons["chat.stop"].tap()

        let composer = app.textFields["chat.composer"]
        let expected = NSPredicate(format: "value == %@", "Check the attachment policy limits.")
        expectation(for: expected, evaluatedWith: composer)
        waitForExpectations(timeout: 10)
    }

    func testTypingTurnsTheMicrophoneBackIntoASendButton() {
        let app = chatApp()

        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("a typed question")

        XCTAssertTrue(app.buttons["chat.send"].waitForExistence(timeout: 5), "text means Send, not the microphone")
        XCTAssertFalse(app.buttons["chat.voice"].exists)
    }
}
