import XCTest

/// The simulator half of G2's 20-minute session: the app, on the iPhone simulator, against the
/// real dev gateway, over a real socket. Connect, send, stream, stop, then a reconnect and a
/// resume through the app's own way out (Settings → Gateway → Disconnect → sign in again).
///
/// Opt-in, because it needs a running gateway and a secret: run it with
/// `NANOBOT_LIVE_URL=http://127.0.0.1:18765 NANOBOT_LIVE_SECRET=$(cat .dev/secret) xcodebuild …`
/// and the values are handed to the app through its launch environment, never typed, so the
/// secret never reaches this log.
///
/// Two beats are the `core-cli session` instrument's, not this one: a file pick and a recording
/// go through system UI a simulator cannot drive, and this gateway has no STT provider. Those
/// are proven with real bytes through the same core in `.dev/session-20min.log`.
final class LiveSessionTests: XCTestCase {

    private var secret: String { ProcessInfo.processInfo.environment["NANOBOT_LIVE_SECRET"] ?? "" }
    private var url: String { ProcessInfo.processInfo.environment["NANOBOT_LIVE_URL"] ?? "" }

    private func liveApp() throws -> XCUIApplication {
        guard !url.isEmpty, !secret.isEmpty else {
            throw XCTSkip("no live gateway: set NANOBOT_LIVE_URL and NANOBOT_LIVE_SECRET")
        }
        let app = XCUIApplication()
        app.launchEnvironment["NANOBOT_LIVE_URL"] = url
        app.launchEnvironment["NANOBOT_LIVE_SECRET"] = secret
        app.launch()
        return app
    }

    func testTheSimulatorRunsATurnAgainstTheLiveGateway() throws {
        let app = try liveApp()

        let connect = app.buttons["connect.submit"]
        XCTAssertTrue(connect.waitForExistence(timeout: 15), "onboarding is the first screen. Tree:\n\(app.debugDescription)")
        connect.tap()

        XCTAssertTrue(
            app.textFields["chat.composer"].waitForExistence(timeout: 25),
            "the socket opened and the greeting arrived. Tree:\n\(app.debugDescription)"
        )
        print("live: connected \(url)")

        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("Reply with exactly the word: pong")
        let send = app.buttons["chat.send"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), "typing turns the microphone into Send")
        send.tap()

        let answer = app.staticTexts.matching(identifier: "chat.answer")
        let deadline = Date().addingTimeInterval(120)
        while answer.count == 0 && Date() < deadline {
            usleep(250_000)
        }
        XCTAssertGreaterThan(answer.count, 0, "an answer arrived over the live socket")
        print("live: streamed \(answer.count) answer row(s)")

        // The turn settles: the button is Send again with an empty field only if canSend is
        // true, so an empty field and no stop button means the turn ended.
        let stopped = Date().addingTimeInterval(180)
        while app.buttons["chat.stop"].exists && Date() < stopped {
            usleep(250_000)
        }
        XCTAssertFalse(app.buttons["chat.stop"].exists, "the turn ended without a stuck spinner")
        print("live: turn settled")
    }

    func testATurnCanBeStoppedMidStream() throws {
        let app = try liveApp()

        XCTAssertTrue(app.buttons["connect.submit"].waitForExistence(timeout: 15))
        app.buttons["connect.submit"].tap()
        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 25))

        let composer = app.textFields["chat.composer"]
        composer.tap()
        // Long enough that the turn is still composing when the tap lands: a 200-line list is
        // finished by a fast model inside the second the button takes to settle, and then there
        // is nothing to stop — a race in this instrument, not in the app.
        composer.typeText("Write a detailed 1200-word explanation of WebSocket framing, with headings and examples.")
        XCTAssertTrue(app.buttons["chat.send"].waitForExistence(timeout: 10))
        app.buttons["chat.send"].tap()

        XCTAssertTrue(app.buttons["chat.stop"].waitForExistence(timeout: 20), "the turn runs")
        app.buttons["chat.stop"].tap()
        print("live: stop sent")

        let deadline = Date().addingTimeInterval(120)
        while app.buttons["chat.stop"].exists && Date() < deadline {
            usleep(250_000)
        }
        XCTAssertFalse(app.buttons["chat.stop"].exists, "the stop settled the turn")
        print("live: stopped turn settled")
    }

    func testAReconnectResumesTheSameChat() throws {
        // P9's reproduction, now the assertion: after Settings → Gateway → Disconnect and a
        // second connect, the app shows the shell with the connection dot reading connected
        // and the session it re-opens replaying its stored history (D46). The core's own
        // reconnect is proven by `core-cli session` (`beat reconnect`, `beat resume`); this is
        // the screen above it.
        let app = try liveApp()

        XCTAssertTrue(app.buttons["connect.submit"].waitForExistence(timeout: 15))
        app.buttons["connect.submit"].tap()
        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 25))

        // One turn first, so the chat has history to resume into.
        let composer = app.textFields["chat.composer"]
        composer.tap()
        composer.typeText("Reply with exactly the word: pong")
        XCTAssertTrue(app.buttons["chat.send"].waitForExistence(timeout: 10))
        app.buttons["chat.send"].tap()
        let settled = Date().addingTimeInterval(180)
        while app.buttons["chat.stop"].exists && Date() < settled {
            usleep(250_000)
        }
        let answersBefore = app.staticTexts.matching(identifier: "chat.answer").count
        XCTAssertGreaterThan(answersBefore, 0)

        // The way out is Settings → Gateway → Disconnect, which forgets the address and the key
        // and returns to onboarding. Signing in again is a fresh bootstrap and a fresh
        // single-use token: the gateway's own reconnect.
        app.buttons["chat.openDrawer"].tap()
        XCTAssertTrue(app.buttons["drawer.settings"].waitForExistence(timeout: 10))
        app.buttons["drawer.settings"].tap()
        app.buttons["settings.gateway"].tap()
        XCTAssertTrue(app.alerts["Disconnect gateway?"].waitForExistence(timeout: 10))
        app.alerts.buttons["Disconnect"].tap()

        XCTAssertTrue(app.buttons["connect.submit"].waitForExistence(timeout: 20), "back at onboarding. Tree:\n\(app.debugDescription)")
        print("live: disconnected")

        // A disconnect forgets the key, so the live launch's prefill is the only key available;
        // the address is prefilled by the environment-backed store.
        app.buttons["connect.submit"].tap()
        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 25), "signed in again")

        // The greeting mints a new chat, which is the gateway's behaviour; opening the earlier
        // session from the drawer replays its history through the same reducer.
        //
        // The dot first: it was idle after a reconnect because the shell only listened for
        // changes it was on screen to see (P9).
        let dot = app.descendants(matching: .any).matching(identifier: "states.dot").firstMatch
        XCTAssertTrue(
            dot.waitForExistence(timeout: 10),
            "the connection dot is on screen. Tree:\n\(app.debugDescription)"
        )
        XCTAssertEqual(
            dot.label,
            "Connection: connected",
            "a reconnect the gateway accepted is a connected shell, not an idle one"
        )

        app.buttons["chat.openDrawer"].tap()
        // The chat we sent into is the one whose preview carries what we said; the greeting a
        // reconnect mints a fresh empty chat above it, so picking row 0 would prove nothing.
        let mine = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'drawer.row.' AND label CONTAINS[c] 'pong'"))
        let deadline = Date().addingTimeInterval(30)
        while mine.count == 0 && Date() < deadline {
            usleep(250_000)
        }
        XCTAssertGreaterThan(mine.count, 0, "the drawer lists the session we used, by its preview. Tree:\n\(app.debugDescription)")
        mine.element(boundBy: 0).tap()

        let replayed = Date().addingTimeInterval(30)
        while app.staticTexts.matching(identifier: "chat.answer").count == 0 && Date() < replayed {
            usleep(250_000)
        }
        XCTAssertGreaterThan(
            app.staticTexts.matching(identifier: "chat.answer").count,
            0,
            "the resumed chat replays its history through the same reducer. Tree:\n\(app.debugDescription)"
        )
        print("live: resumed chat with \(app.staticTexts.matching(identifier: "chat.answer").count) answer row(s)")
    }

    /// The owner's bug, on a real socket: a banner appears, and "Try again" asks the core for a
    /// new handshake — a fresh single-use token, a new socket, a real `ready`. The debug menu
    /// raises the banner without touching the connection, so the banner can only clear if the
    /// button actually reconnected: no other path sets the state back to open.
    func testTheBannersTryAgainReconnectsOnTheLiveGateway() throws {
        let app = try liveApp()

        XCTAssertTrue(app.buttons["connect.submit"].waitForExistence(timeout: 15))
        app.buttons["connect.submit"].tap()
        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 25), "connected. Tree:\n\(app.debugDescription)")

        XCTAssertTrue(app.buttons["chat.debug"].waitForExistence(timeout: 10))
        app.buttons["chat.debug"].tap()
        XCTAssertTrue(app.buttons["debug.failure.unreachable"].waitForExistence(timeout: 10))
        app.buttons["debug.failure.unreachable"].tap()

        let banner = app.staticTexts["states.banner"]
        XCTAssertTrue(banner.waitForExistence(timeout: 10), "the failure raises the banner")
        XCTAssertEqual(banner.label, "No gateway at that address. Check the address and that the gateway is running.")

        let retry = app.buttons["states.retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 5), "a failed handshake offers the retry")
        retry.tap()
        print("live: tapped try again")

        // Connecting… while the handshake runs, then nothing at all once the gateway greeted the
        // new socket. A button wired to nothing leaves the failure on screen forever.
        let cleared = Date().addingTimeInterval(30)
        while app.staticTexts["states.banner"].exists && Date() < cleared {
            usleep(250_000)
        }
        XCTAssertFalse(
            app.staticTexts["states.banner"].exists,
            "the retry reached the gateway and the socket is open again. Tree:\n\(app.debugDescription)"
        )
        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 10), "and the shell is still the shell")
        print("live: retry reconnected")
    }
}
