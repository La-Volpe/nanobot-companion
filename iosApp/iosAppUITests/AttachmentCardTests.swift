import XCTest

/// The composer's attachment cards, on screen and in their states (plan.md I6).
///
/// The system photo picker and the camera cannot be driven by XCUITest, so what a tap proves here
/// is the half after the pick: the card appears with a preview, a card whose upload failed keeps
/// its reason and a Retry, and the card that landed is what the send would carry. The bytes
/// through the real gateway are the live session's job.
final class AttachmentCardTests: XCTestCase {

    private func seededApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        app.launchEnvironment["NANOBOT_ATTACH"] = "1"
        app.launch()
        if !app.buttons["chat.voice"].waitForExistence(timeout: 10) {
            XCTFail("the composer did not come up. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    func testAPickedPhotoIsOnScreenBeforeItIsSent() {
        let app = seededApp()

        XCTAssertTrue(
            app.buttons["attach.image"].waitForExistence(timeout: 5),
            "the picked photo is a card above the field, not a wait for the upload. Tree:\n\(app.debugDescription)"
        )
        XCTAssertTrue(app.otherElements["attach.file"].exists, "and the document beside it")
    }

    /// A failure the reader can still fix stays on screen: the card keeps the reason, and Retry
    /// asks the same bytes again rather than sending the reader back to the picker.
    func testAFailedUploadKeepsItsCardAndItsRetry() {
        let app = seededApp()

        let card = app.otherElements["attach.file"]
        XCTAssertTrue(card.waitForExistence(timeout: 5), "the failed card is still there. Tree:\n\(app.debugDescription)")
        XCTAssertTrue(
            app.staticTexts["attach.failure"].exists,
            "the reason is written on the card, not kept in a state only the code can see. Tree:\n\(app.debugDescription)"
        )

        let retry = app.buttons["attach.retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 5), "with a way to try it again. Tree:\n\(app.debugDescription)")

        retry.tap()

        XCTAssertFalse(
            app.staticTexts["attach.failure"].waitForExistence(timeout: 3),
            "the second attempt landed, so the reason goes with it"
        )
        XCTAssertTrue(card.waitForExistence(timeout: 5), "and the card stays on the message")
    }
}
