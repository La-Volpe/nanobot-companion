import XCTest

/// The composer flow a tap can prove: the meta row's three controls open
/// their sheets, a prompt typed during a turn waits on the chip with Send now / Delete, a slash
/// opens the commands sheet, and a code block's Copy confirms.
///
/// The scripted chat is DEBUG-only and answers `loadPalette` with a canned command list; which
/// rows the gateway sends is the core's (`PaletteTest`).
final class ComposerTests: XCTestCase {

    private func chatApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_SCENARIO"] = "connected"
        app.launchEnvironment["NANOBOT_START_CONNECTED"] = "1"
        app.launchEnvironment["NANOBOT_THREAD"] = "scripted"
        app.launch()
        // The composer's field proves the shell; an empty field offers the microphone, not Send.
        if !app.textFields["chat.composer"].waitForExistence(timeout: 10) {
            XCTFail("the chat is the connected screen. Tree:\n\(app.debugDescription)")
        }
        return app
    }

    func testTheMetaRowOpensTheModelSheetAndDismissesIt() {
        let app = chatApp()

        XCTAssertTrue(app.buttons["composer.access"].exists, "the access chip")
        XCTAssertTrue(app.buttons["composer.usage"].exists, "the context ring")
        let model = app.buttons["composer.model"]
        XCTAssertTrue(model.exists, "the model pill")
        model.tap()

        XCTAssertTrue(app.staticTexts["Choose model"].waitForExistence(timeout: 5), "the sheet says what it is")
        // "Manage presets" is gone, and the built-in preset is not offered at all: the scripted
        // launch seeds the owner's own three presets, so this is the sheet's real list.
        XCTAssertFalse(app.buttons["composer.model.manage"].exists, "the phone does not manage presets")
        XCTAssertFalse(
            app.buttons["composer.model.default"].waitForExistence(timeout: 2),
            "the gateway's built-in preset is not a choice"
        )
        let primary = app.buttons["composer.model.primary"]
        XCTAssertTrue(primary.waitForExistence(timeout: 5), "the gateway's own presets are still here")
        primary.tap()
        XCTAssertFalse(app.staticTexts["Choose model"].waitForExistence(timeout: 2), "picking closes the sheet")
    }

    func testTheAccessChipOffersDefaultAndFull() {
        let app = chatApp()

        app.buttons["composer.access"].tap()
        XCTAssertTrue(app.staticTexts["Workspace access"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["composer.access.default"].exists)
        XCTAssertTrue(app.buttons["composer.access.full"].exists)

        app.buttons["composer.access.full"].tap()
        XCTAssertTrue(
            app.buttons["Workspace access: full"].waitForExistence(timeout: 5),
            "the chip says Full until the gateway refuses it"
        )
    }

    func testAPromptTypedDuringATurnQueuesWithSendNowAndDelete() {
        let app = chatApp()

        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("start a turn")
        app.buttons["chat.send"].tap()

        XCTAssertTrue(app.buttons["chat.stop"].waitForExistence(timeout: 5), "an empty field during a turn means Stop")

        composer.tap()
        composer.typeText("and this waits")
        let send = app.buttons["chat.send"]
        XCTAssertTrue(send.waitForExistence(timeout: 5), "typing turns the button back into Send")
        send.tap()

        let chip = app.staticTexts["composer.queue"]
        XCTAssertTrue(chip.waitForExistence(timeout: 5), "the queued prompt shows on its chip. Tree:\n\(app.debugDescription)")
        XCTAssertTrue(app.buttons["composer.queue.sendNow"].exists, "Send now")
        XCTAssertTrue(app.buttons["composer.queue.delete"].exists, "Delete")

        app.buttons["composer.queue.delete"].tap()
        XCTAssertFalse(chip.waitForExistence(timeout: 2), "Delete drops the prompt")
    }

    func testASlashOpensTheCommandsSheetAndAPickFillsTheField() {
        let app = chatApp()

        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("/")

        XCTAssertTrue(app.staticTexts["Commands"].waitForExistence(timeout: 5), "the slash opens the commands sheet")
        let stop = app.buttons["composer.palette.c1"]
        XCTAssertTrue(stop.waitForExistence(timeout: 5), "a row for /stop. Tree:\n\(app.debugDescription)")
        stop.tap()

        XCTAssertEqual(composer.value as? String, "/stop ", "the row's own token lands in the field")
    }

    func testACodeBlockCopiesAndConfirms() {
        let app = chatApp()

        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("show me code")
        app.buttons["chat.send"].tap()

        let copy = app.buttons["reading.copy"]
        XCTAssertTrue(copy.waitForExistence(timeout: 10), "the fence draws with its Copy. Tree:\n\(app.debugDescription)")
        copy.tap()

        // The confirmation lasts 1.5 s, which is shorter than XCUITest's own one-second poll, so
        // this polls faster instead of racing the window (the window itself is pinned by
        // `ComposerViewModelTests.copyConfirmsFor1_5s`).
        var sawCopied = false
        let deadline = Date().addingTimeInterval(1.4)
        while Date() < deadline {
            if app.buttons["reading.copied"].exists {
                sawCopied = true
                break
            }
            usleep(100_000)
        }
        XCTAssertTrue(sawCopied, "Copy confirms with a checkmark")
        XCTAssertTrue(app.buttons["reading.copy"].waitForExistence(timeout: 5), "and returns after 1.5 s")
    }
}
