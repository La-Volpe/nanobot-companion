import Foundation
import Testing
import UIKit
import core
@testable import iosApp

/// The rows a turn's media becomes, and the two resting failures a loader may end in (plan.md
/// I3). The pixels are the screenshot's job; what is asserted here is that a picture that will
/// not load says so instead of leaving a blank box or a spinner spinning for ever.
@MainActor
struct MediaRowTests {

    /// One stored page, reduced by the core, exactly as `ChatHandle.open()` reduces it. The
    /// media is in the frame the gateway sends: `message.media_urls`
    /// (`nanobot/webui/transcript.py:1567`).
    private let page = """
    {"events":[
      {"event":"user_message","chat_id":"chat-1","turn_id":"turn-1","text":"what is this",
       "media_urls":[{"kind":"image","url":"/api/media/sig/user.png","name":"user.png"}]},
      {"event":"stream_end","chat_id":"chat-1","turn_id":"turn-1","stream_id":"s1","text":"a chart"},
      {"event":"message","chat_id":"chat-1","turn_id":"turn-1","text":"here",
       "media_urls":[
         {"url":"/api/media/sig/chart.png","name":"chart.png"},
         {"url":"/api/media/sig/clip.mp4","name":"clip.mp4"},
         {"url":"/api/media/sig/note.m4a","name":"note.m4a"},
         {"url":"/api/media/sig/report.pdf","name":"report.pdf"}]},
      {"event":"turn_end","chat_id":"chat-1","turn_id":"turn-1","outcome":"completed"}
    ]}
    """

    private func thread() -> ChatThreadState {
        let state = HistoryReplay.shared.fromPage(page: page, chatId: "chat-1", nowMs: 0)
        return ChatThreadState(state: state, mediaBase: "http://127.0.0.1:18765")
    }

    @Test func anAnswerWithMediaDrawsOneRowPerFileWithItsKind() throws {
        let turn = try #require(thread().turns.first)

        #expect(turn.media.map(\.kind) == [.image, .video, .audio, .file], "the extension decides the kind")
        #expect(turn.media.map(\.name) == ["chart.png", "clip.mp4", "note.m4a", "report.pdf"])
    }

    @Test func theRowsCarryALoadableAbsoluteURL() throws {
        let turn = try #require(thread().turns.first)
        let chart = try #require(turn.media.first)

        #expect(
            chart.url?.absoluteString == "http://127.0.0.1:18765/api/media/sig/chart.png",
            "the gateway signs a path; the row needs an origin to open it"
        )
    }

    @Test func thePromptDrawsTheFilesItWasSentWith() throws {
        let turn = try #require(thread().turns.first)

        #expect(turn.promptMedia.map(\.name) == ["user.png"])
        #expect(turn.promptMedia.first?.url?.absoluteString == "http://127.0.0.1:18765/api/media/sig/user.png")
    }

    @Test func theThreadPutsMediaInReadingOrder() {
        let kinds = thread().rows.map { row -> String in
            switch row.kind {
            case .user: return "prompt"
            case .answer: return "answer"
            case .media(let media): return "media:\(media.kind)"
            case .activity: return "activity"
            }
        }

        #expect(
            kinds == ["media:image", "prompt", "answer", "media:image", "media:video", "media:audio", "media:file"],
            "what was sent sits above the prompt, what came back under the answer"
        )
    }

    /// The gateway can send an entry it could not sign (`media_api.py:194`, an empty url). That is
    /// a card that says unavailable, not an image row waiting on a request nobody can make.
    @Test func anEntryTheGatewayCouldNotSignIsNotLoadable() throws {
        let state = HistoryReplay.shared.fromPage(
            page: """
            {"events":[{"event":"stream_end","chat_id":"chat-1","turn_id":"turn-1","stream_id":"s1","text":"x"},
              {"event":"message","chat_id":"chat-1","turn_id":"turn-1","text":"x",
              "media_urls":[{"url":"","name":"gone.png"}]}]}
            """,
            chatId: "chat-1",
            nowMs: 0
        )

        let rows = try #require(ChatThreadState(state: state, mediaBase: "http://127.0.0.1:18765").turns.first).media

        #expect(rows.isEmpty, "an entry with no url has nothing to draw or load")
    }
}

/// A row's load: the placeholder, the picture, or the sentence with a retry on it.
@MainActor
struct MediaLoaderTests {

    private let url = URL(string: "http://127.0.0.1:18765/api/media/sig/chart.png")!

    /// A real 1x1 bitmap, so "ready" means something a view can draw.
    private func pixel() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { context in
            UIColor.orange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        }
    }

    @Test func aFailedFetchSaysSoInsteadOfDrawingABlankBox() async {
        let loader = MediaLoader { _ in throw MediaLoadError.notAnImage }

        await loader.load(url)

        #expect(loader.phase == .failed, "a row that cannot load is a sentence and a retry, not an empty frame")
        #expect(loader.image == nil)
    }

    @Test func aLoadThatSucceedsRestsOnReady() async {
        let image = pixel()
        let loader = MediaLoader { _ in image }

        await loader.load(url)

        #expect(loader.phase == .ready)
        #expect(loader.image != nil)
    }

    @Test func noPhaseIsLoadingOnceTheLoadHasReturned() async {
        for succeeds in [true, false] {
            let image = pixel()
            let loader = MediaLoader { _ in
                if succeeds { return image }
                throw MediaLoadError.refused(404)
            }

            await loader.load(url)

            #expect(loader.phase != .loading, "a spinner that never stops is the bug this state exists to prevent")
        }
    }

    @Test func retryAfterAFailureLoadsTheSameURL() async {
        final class Attempts { var count = 0 }
        let attempts = Attempts()
        let image = pixel()
        let loader = MediaLoader { _ in
            attempts.count += 1
            if attempts.count == 1 { throw MediaLoadError.refused(404) }
            return image
        }

        await loader.load(url)
        #expect(loader.phase == .failed)

        await loader.retry()

        #expect(loader.phase == .ready, "the retry is the same request again")
        #expect(loader.image != nil)
        #expect(attempts.count == 2)
    }

    @Test func theSameRowIsNotRefetchedOnEveryRedraw() async {
        final class Attempts { var count = 0 }
        let attempts = Attempts()
        let image = pixel()
        let loader = MediaLoader { _ in
            attempts.count += 1
            return image
        }

        await loader.load(url)
        await loader.load(url)

        #expect(attempts.count == 1, "the transcript scrolls past the same row constantly")
    }
}
