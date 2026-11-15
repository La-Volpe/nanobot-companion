import Foundation
import Testing
@testable import iosApp

/// The audio row's state machine, driven by a fake transport: what a tap does, and what the
/// progress line reads. The simulator has no output device, so audibility is a phone's job.
@MainActor
struct AudioPlaybackTests {

    final class FakeTransport: AudioTransport {
        var loaded: [URL] = []
        var plays = 0
        var pauses = 0
        var failing = false
        var isPlaying = false
        var currentTime: TimeInterval = 0
        var duration: TimeInterval = 0

        func load(_ url: URL) async throws {
            loaded.append(url)
            if failing { throw AudioPlaybackError.notPlayable }
        }

        func play() {
            plays += 1
            isPlaying = true
        }

        func pause() {
            pauses += 1
            isPlaying = false
        }
    }

    private let url = URL(string: "http://127.0.0.1:18765/api/media/sig/note.m4a")!

    @Test func theFirstTapLoadsTheRecordingAndPlaysIt() async {
        let transport = FakeTransport()
        let player = AudioPlayerModel(url: url, transport: transport)

        await player.toggle()

        #expect(player.phase == .playing)
        #expect(transport.loaded == [url])
        #expect(transport.plays == 1)
    }

    @Test func theSecondTapPausesAndTheThirdResumes() async {
        let transport = FakeTransport()
        let player = AudioPlayerModel(url: url, transport: transport)

        await player.toggle()
        await player.toggle()
        #expect(player.phase == .paused)
        #expect(transport.pauses == 1)

        await player.toggle()
        #expect(player.phase == .playing)
        #expect(transport.loaded.count == 1, "a resume does not re-read the file")
    }

    @Test func aRecordingThatWillNotLoadSaysSoAndCanBeRetried() async {
        let transport = FakeTransport()
        transport.failing = true
        let player = AudioPlayerModel(url: url, transport: transport)

        await player.toggle()
        #expect(player.phase == .failed, "the row is a sentence with a retry, not a spinner")

        transport.failing = false
        await player.retry()

        #expect(player.phase == .playing)
        #expect(transport.loaded == [url, url])
    }

    @Test func theProgressLineIsTheElapsedFraction() async {
        let transport = FakeTransport()
        transport.duration = 20
        transport.currentTime = 5
        let player = AudioPlayerModel(url: url, transport: transport)

        player.tick()

        #expect(player.elapsed == 5)
        #expect(abs(player.progress - 0.25) < 0.0001)
    }

    @Test func aClipWithNoKnownLengthDrawsNoLineRatherThanNaN() {
        let transport = FakeTransport()
        let player = AudioPlayerModel(url: url, transport: transport)

        player.tick()

        #expect(player.progress == 0, "a NaN width would crash the layout of the whole thread")
        #expect(!player.progress.isNaN)
    }
}
