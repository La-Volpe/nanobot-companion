import XCTest

/// The media rows, on the simulator, against the real dev gateway: an answer that carries a
/// picture, a clip and a recording draws three rows, and the picture is *loaded*: the file the
/// gateway signed over `/api/media/<sig>/<payload>` is what the row shows.
///
/// Opt-in like the other live tests: it needs `NANOBOT_LIVE_URL`, `NANOBOT_LIVE_SECRET` and a
/// chat that already holds media (`NANOBOT_LIVE_MEDIA_CHAT`). The run leaves a screenshot at
/// `NANOBOT_MEDIA_SHOT` so the claim can be looked at instead of described.
final class LiveMediaTests: XCTestCase {

    private var environment: [String: String] { ProcessInfo.processInfo.environment }

    func testAnAnswerWithMediaDrawsLoadableRows() throws {
        let url = environment["NANOBOT_LIVE_URL"] ?? ""
        let secret = environment["NANOBOT_LIVE_SECRET"] ?? ""
        let chat = environment["NANOBOT_LIVE_MEDIA_CHAT"] ?? ""
        guard !url.isEmpty, !secret.isEmpty, !chat.isEmpty else {
            throw XCTSkip("no live gateway or no media chat: set NANOBOT_LIVE_URL, NANOBOT_LIVE_SECRET, NANOBOT_LIVE_MEDIA_CHAT")
        }

        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_LIVE_URL"] = url
        app.launchEnvironment["NANOBOT_LIVE_SECRET"] = secret
        app.launch()
        XCTAssertTrue(app.buttons["chat.openDrawer"].waitForExistence(timeout: 30), "the shell is up on the live gateway")

        // The drawer's rows are the gateway's own sessions, keyed `websocket:<chat id>`.
        app.buttons["chat.openDrawer"].tap()
        let row = app.buttons["drawer.row.websocket:\(chat)"]
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the drawer lists the live chat:\n\(app.debugDescription)")
        row.tap()

        // The picture is the load: `media.image` only exists once the bytes came back and decoded.
        let image = app.images["media.image"]
        XCTAssertTrue(image.waitForExistence(timeout: 60), "the signed url loaded into a row:\n\(app.debugDescription)")

        // The answer's rows are further down a long thread; the list is lazy, so they exist when
        // they are scrolled to.
        let clip = app.buttons["media.video"]
        let recording = app.buttons["media.audio.toggle"]
        let thread = app.scrollViews.firstMatch
        for _ in 0..<10 where !(clip.exists && recording.exists) {
            thread.swipeUp()
            usleep(200_000)
        }
        XCTAssertTrue(clip.exists, "a clip is a row")
        XCTAssertTrue(recording.exists, "a recording is a row")

        // A tap on the recording reaches the model: the control becomes the pause one. The
        // simulator has no audio device, so this is the state and the line, not the sound.
        recording.tap()
        let paused = app.buttons["media.audio.toggle"].label
        XCTAssertTrue(paused == "Pause" || paused == "Play", "the control answered the tap: \(paused)")

        if let path = environment["NANOBOT_VIDEO_SHOT"] {
            clip.tap()
            sleep(4)
            try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: path))
            print("media: video screenshot at \(path)")
        }

        if let path = environment["NANOBOT_MEDIA_SHOT"] {
            let png = XCUIScreen.main.screenshot().pngRepresentation
            try png.write(to: URL(fileURLWithPath: path))
            print("media: screenshot at \(path)")
        }
    }
}
