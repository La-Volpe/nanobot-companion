import Foundation
import Testing
@testable import iosApp

/// The rule the owner reported missing, taken from the WebUI rather than invented:
/// `ThreadViewport.tsx:1002` measures the distance from the end and hands it to
/// `thread-motion.ts:353`, which pauses following anywhere but the tail. The iOS twin of
/// `ThreadViewportTest` on Android.
struct ThreadViewportTests {

    @Test func aReaderAtTheEndFollowsNewContent() {
        #expect(ThreadViewport().scrolled(distanceFromBottom: 0).followsNewContent,
                "a reader at the end is brought along")
    }

    @Test func aReaderWhoScrolledUpIsLeftWhereTheyAre() {
        let away = ThreadViewport().scrolled(distanceFromBottom: 600)

        #expect(away.following == false)
        #expect(away.followsNewContent == false, "new content must not yank a browsing reader down")
    }

    @Test func returningToTheEndResumesFollowing() {
        let away = ThreadViewport().scrolled(distanceFromBottom: 600)
        let back = away.scrolled(distanceFromBottom: 0)

        #expect(back.following)
        #expect(back.followsNewContent, "at the end again, the stream follows again")
    }

    /// `NEAR_BOTTOM_PX = 48` (ThreadViewport.tsx:80): the web's own threshold, not a round guess.
    @Test func theThresholdIsTheWebsOwn() {
        #expect(ThreadViewport().scrolled(distanceFromBottom: 47).following)
        #expect(ThreadViewport().scrolled(distanceFromBottom: 48).following == false)
    }

    @Test func askingForTheTailFollowsAgain() {
        #expect(ThreadViewport().scrolled(distanceFromBottom: 600).pinned().following,
                "the reader asked for the latest, so the stream follows")
    }
}
