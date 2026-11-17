import AVFoundation
import Foundation

/// What an audio row is doing. `failed` is a resting state with a retry on it, like an image's:
/// nothing here may leave a row spinning after the load has come back.
enum AudioPhase: Equatable {
    case idle
    case loading
    case playing
    case paused
    case failed
}

/// The transport one audio row needs. `AVPlayer` is the real one; a fake in a test is the other,
/// which is how play/pause and the progress line are proved without an audio device.
@MainActor
protocol AudioTransport: AnyObject {
    var isPlaying: Bool { get }
    var currentTime: TimeInterval { get }
    var duration: TimeInterval { get }

    func load(_ url: URL) async throws
    func play()
    func pause()
}

/// One audio row's transport, on `AVPlayer`, the framework's own streaming player, which handles
/// a byte-range read of a long recording without downloading the whole file first.
@MainActor
final class AVPlayerTransport: AudioTransport {
    private let player = AVPlayer()
    private var item: AVPlayerItem?

    var isPlaying: Bool { player.timeControlStatus == .playing }
    var currentTime: TimeInterval { player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0 }
    var duration: TimeInterval {
        guard let seconds = item?.duration.seconds, seconds.isFinite else { return 0 }
        return seconds
    }

    func load(_ url: URL) async throws {
        let item = AVPlayerItem(url: url)
        self.item = item
        player.replaceCurrentItem(with: item)
        // `AVPlayer` will not report a duration until the asset is ready; a row that needs one for
        // its progress line waits here rather than drawing 0:00 for a clip it could measure.
        let ready = try await item.asset.load(.isPlayable)
        if !ready { throw AudioPlaybackError.notPlayable }
    }

    func play() { player.play() }
    func pause() { player.pause() }
}

enum AudioPlaybackError: Error, Equatable {
    case notPlayable
}

/// An audio row's state: play, pause, and the line that says how far in it is.
@MainActor
final class AudioPlayerModel: ObservableObject {
    @Published private(set) var phase: AudioPhase = .idle
    @Published private(set) var elapsed: TimeInterval = 0
    /// 0…1, and 0 for a clip whose length nobody has told us yet, never NaN.
    @Published private(set) var progress: Double = 0

    private let url: URL
    private let transport: AudioTransport

    init(url: URL, transport: AudioTransport? = nil) {
        self.url = url
        self.transport = transport ?? AVPlayerTransport()
    }

    /// One control, three states: the first tap loads and plays, the second pauses, the third
    /// resumes without re-reading the file.
    func toggle() async {
        switch phase {
        case .playing:
            transport.pause()
            phase = .paused
        case .paused:
            transport.play()
            phase = .playing
        case .idle, .failed:
            await start()
        case .loading:
            // A second tap while the file is still coming does nothing: two loads of the same
            // recording would race each other onto one player.
            break
        }
    }

    func retry() async {
        await start()
    }

    private func start() async {
        phase = .loading
        do {
            try await transport.load(url)
            transport.play()
            phase = .playing
        } catch {
            phase = .failed
        }
    }

    /// The view's tick: the row reads the transport rather than the transport pushing, so a paused
    /// clip costs nothing. A recording whose length the asset has not reported yet draws no line
    /// instead of a NaN width.
    func tick() {
        elapsed = transport.currentTime
        let duration = transport.duration
        progress = duration > 0 ? min(max(elapsed / duration, 0), 1) : 0
    }
}
