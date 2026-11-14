import XCTest

/// The icon is part of the product, so it gets a check instead of a glance: SpringBoard has to
/// find an AppIcon in the bundle and draw it under the app's name. Without the asset the tile is
/// the grey placeholder grid, which is the state this test was written against.
///
/// The tile has to be *on screen*: an element can exist in SpringBoard's tree while sitting on a
/// page nobody is looking at, and a test that accepts that would pass with no icon installed.
///
/// Opt-in on purpose. Which page SpringBoard is showing is SpringBoard's state, not the app's, so
/// in a full suite run the swipe sometimes lands nowhere and the check fails for a reason that has
/// nothing to do with the icon. Run it when the icon changes, and read the attachment:
///
///     TEST_RUNNER_NANOBOT_HOME_ICON=1 xcodebuild test \
///       -only-testing:iosAppUITests/HomeScreenIconTests ...
final class HomeScreenIconTests: XCTestCase {

    func testSpringBoardDrawsTheAppIcon() throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["NANOBOT_HOME_ICON"] == "1",
            "opt-in: set TEST_RUNNER_NANOBOT_HOME_ICON=1 to photograph the home-screen tile"
        )
        XCUIDevice.shared.press(.home)

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let tile = springboard.icons["nanobot Companion"]

        for _ in 0..<4 where !tile.isHittable {
            springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.45))
                .press(forDuration: 0.05, thenDragTo: springboard.coordinate(
                    withNormalizedOffset: CGVector(dx: 0.08, dy: 0.45)))
            // A page turn is animated; let the tile's frame settle before asking again.
            _ = tile.waitForExistence(timeout: 1)
        }

        XCTAssertTrue(tile.isHittable, "the app's tile is not on any home-screen page it reached")

        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "home-screen-icon"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
