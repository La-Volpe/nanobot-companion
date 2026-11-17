import Foundation
import UIKit

/// A picture, a clip or a recording on a turn (the core's `MediaAttachment`, `Media.kt`).
/// The url is what the gateway signed; the core resolved it against the gateway's origin.
struct MediaRow: Identifiable, Equatable {
    let id: String
    let name: String
    let url: URL?
    let kind: MediaRowKind

    /// A row with no loadable url still draws, as a file card that says it is unavailable,
    /// because an entry the gateway could not sign is not an image to be waited for.
    var loadable: Bool { url != nil }
}

enum MediaRowKind: Equatable {
    case image
    case video
    case audio
    case file

    /// The three-letter face a file card carries, as the composer's own cards do.
    var face: String {
        switch self {
        case .image: "IMG"
        case .video: "VID"
        case .audio: "AUD"
        case .file: "FILE"
        }
    }
}

/// What a row's bytes are doing: the placeholder, the picture, or the sentence with a retry.
/// `failed` is a resting state, so a row can never sit on a spinner forever.
enum MediaPhase: Equatable {
    case idle
    case loading
    case ready
    case failed
}

enum MediaLoadError: Error, Equatable {
    /// The gateway answered, and the answer was not a picture. `UIImage(data:)` returning nil is
    /// the commonest way a media row goes blank, so it is a failure rather than an empty image.
    case notAnImage
    case refused(Int)
}

@MainActor
final class MediaLoader: ObservableObject {
    @Published private(set) var phase: MediaPhase = .idle
    @Published private(set) var image: UIImage?

    private var url: URL?
    private let fetch: (URL) async throws -> UIImage

    init(fetch: @escaping (URL) async throws -> UIImage = MediaLoader.fetch) {
        self.fetch = fetch
    }

    /// Load once. A row that already has its picture is not refetched: the transcript scrolls
    /// past the same rows constantly, and a decode per redraw is what stutters the thread.
    func load(_ url: URL) async {
        self.url = url
        guard phase == .idle else { return }
        await run()
    }

    func retry() async {
        guard let url else { return }
        phase = .idle
        image = nil
        self.url = url
        await run()
    }

    private func run() async {
        guard let url else { return }
        phase = .loading
        do {
            let loaded = try await fetch(url)
            image = loaded
            phase = .ready
        } catch {
            image = nil
            phase = .failed
        }
    }

    /// The real transport: the signed path needs no bearer header (`media_api.py:207` checks the
    /// signature), so the loader adds nothing to the url the core resolved.
    static func fetch(_ url: URL) async throws -> UIImage {
        let (data, response) = try await URLSession.shared.data(from: url)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw MediaLoadError.refused(http.statusCode)
        }
        guard let image = UIImage(data: data) else { throw MediaLoadError.notAnImage }
        return image
    }
}
