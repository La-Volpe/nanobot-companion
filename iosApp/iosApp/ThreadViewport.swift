import Foundation

/// Whether the thread follows its own tail, which is the WebUI's rule and not a guess
/// (`ThreadViewport.tsx:1002` measures the distance from the end and hands it to
/// `thread-motion.ts:353`; anything but the tail pauses the follow).
///
/// The view measures; this decides. A reader at the end is brought along as the answer grows,
/// a reader who scrolled up keeps their place, and coming back to the end resumes following.
/// The Android twin is `ThreadViewport.kt`.
struct ThreadViewport: Equatable {

    /// `NEAR_BOTTOM_PX` (webui `ThreadViewport.tsx:80`): the web's own threshold, not a round guess.
    static let nearBottom: CGFloat = 48

    var following: Bool = true

    /// The reader's own scroll landed [distanceFromBottom] from the end, whether they scrolled
    /// there or the gesture settled there. The end resumes the follow; anywhere else pauses it.
    func scrolled(distanceFromBottom: CGFloat) -> ThreadViewport {
        ThreadViewport(following: distanceFromBottom < Self.nearBottom)
    }

    /// A prompt, or the scroll-to-latest control: the reader asked to see the tail.
    func pinned() -> ThreadViewport { ThreadViewport(following: true) }

    /// Whether new streamed content should bring the tail into view.
    var followsNewContent: Bool { following }
}
