import XCTest

/// Hold to talk, as a thumb would prove it (T48): the recording starts on touch-down and shows its
/// timer, a quick tap is discarded with a one-time "Hold to talk" hint, a drag up locks the
/// recording so the button becomes Send and a cancel target appears, and while the gateway
/// transcribes the button is the feedback while the composer keeps working.
///
/// The simulator has no microphone and the system pickers cannot be driven by XCUITest: the
/// scripted launch substitutes a recorder and a transcriber (`ScriptedRecorder`, `ScriptedVoice`),
/// so what is proven here is the app's own states. The real microphone through a real gateway is
/// the owner's phone.
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

    /// A hold under 800 ms is a tap: nothing is sent, and the first one explains the gesture.
    func testAQuickTapSaysHoldToTalkOnce() {
        let app = chatApp()

        app.buttons["chat.voice"].press(forDuration: 0.3)

        let hint = app.staticTexts["Hold to talk"]
        XCTAssertTrue(
            hint.waitForExistence(timeout: 5),
            "a tap under the floor sends nothing and says what to do instead. Tree:\n\(app.debugDescription)"
        )
        XCTAssertTrue(app.buttons["chat.voice"].exists, "and the button is a microphone again")

        app.buttons["voice.notice.dismiss"].tap()
        app.buttons["chat.voice"].press(forDuration: 0.3)
        XCTAssertFalse(hint.exists, "the hint is remembered, so the second tap gets no nagging")
    }

    /// The timer the owner could not see. A hold locks when it is dragged up, so the finger is
    /// gone and the meter stays on screen — which is what makes the clock observable at all: an
    /// XCUITest press blocks until it is released.
    func testTheTimerIsDrawnAndCountsWhileRecording() {
        let app = chatApp()
        let voice = app.buttons["chat.voice"]
        let start = voice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let up = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))

        start.press(forDuration: 0.9, thenDragTo: up)

        let timer = app.staticTexts["voice.timer"]
        XCTAssertTrue(
            timer.waitForExistence(timeout: 5),
            "the meter draws the model's clock. Tree:\n\(app.debugDescription)"
        )
        let first = Self.seconds(timer.label)
        XCTAssertNotNil(first, "the clock reads m:ss, not a placeholder: \(timer.label)")

        // It counts: the ticker moves the model's elapsed clock, and the meter draws it again.
        Thread.sleep(forTimeInterval: 1.6)
        let second = Self.seconds(timer.label)
        XCTAssertNotNil(second)
        XCTAssertGreaterThan(second ?? 0, first ?? 0, "the clock counts while the recording runs")

        app.buttons["voice.cancel"].tap()
    }

    /// "0:07" -> 7. Nil for anything that is not a clock.
    private static func seconds(_ label: String) -> Int? {
        let parts = label.split(separator: ":")
        guard parts.count == 2, let minutes = Int(parts[0]), let secs = Int(parts[1]) else { return nil }
        return minutes * 60 + secs
    }

    /// Drag up while the finger is down: the recording locks, the button becomes Send, and the
    /// cancel target is there. Cancel throws the recording away without a request.
    func testDragUpLocksAndCancelSendsNothing() {
        let app = chatApp()
        let voice = app.buttons["chat.voice"]
        let start = voice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let up = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))

        start.press(forDuration: 0.9, thenDragTo: up)

        XCTAssertTrue(
            app.buttons["chat.send"].waitForExistence(timeout: 5),
            "a locked recording is finished by the same button, now Send. Tree:\n\(app.debugDescription)"
        )
        let cancel = app.buttons["voice.cancel"]
        XCTAssertTrue(cancel.exists, "and there is a way to throw it away. Tree:\n\(app.debugDescription)")

        cancel.tap()

        XCTAssertFalse(app.buttons["voice.cancel"].waitForExistence(timeout: 2), "the recording is gone")
        let composer = app.textFields["chat.composer"].value as? String ?? ""
        XCTAssertTrue(
            composer.isEmpty || composer == "Type a message…",
            "a cancel leaves the field exactly as it was, got \(composer)"
        )
    }

    /// Drag up, then tap the Send the lock turned the button into: the transcript lands in the
    /// field, and it is never sent on the reader's behalf.
    func testDragUpLockThenSendLandsTheTranscript() {
        let app = chatApp()
        let voice = app.buttons["chat.voice"]
        let start = voice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let up = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))

        start.press(forDuration: 0.9, thenDragTo: up)
        XCTAssertTrue(app.buttons["chat.send"].waitForExistence(timeout: 5))
        app.buttons["chat.send"].tap()

        let composer = app.textFields["chat.composer"]
        let expected = NSPredicate(format: "value == %@", "Check the attachment policy limits.")
        expectation(for: expected, evaluatedWith: composer)
        waitForExpectations(timeout: 15)
    }

    /// While the gateway transcribes, the button is the feedback — and the composer keeps working,
    /// which is the owner's correction to the brief: the field is editable and still sends.
    func testTranscribingLeavesTheComposerUsable() {
        let app = chatApp()

        app.buttons["chat.voice"].press(forDuration: 1.0)

        let transcribing = app.staticTexts["Transcribing…"]
        XCTAssertTrue(
            transcribing.waitForExistence(timeout: 5),
            "the wait is a written state as well as an arc. Tree:\n\(app.debugDescription)"
        )
        XCTAssertFalse(app.buttons["chat.voice"].isEnabled, "the voice job is the one the button cannot do")

        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("typed while waiting")
        XCTAssertEqual(composer.value as? String, "typed while waiting", "the composer is not locked")

        // The scripted gateway answers after 4 s; the transcript lands after the typing.
        let expected = NSPredicate(format: "value CONTAINS %@", "Check the attachment policy limits.")
        expectation(for: expected, evaluatedWith: composer)
        waitForExpectations(timeout: 15)
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
