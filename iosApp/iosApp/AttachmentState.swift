import Foundation

/// What the composer may attach, and why it may not (plan.md I6).
///
/// The numbers are the gateway's (`attachment_policy.py`: max_count 4, 6 MiB each, 24 MiB
/// total, one video ≤ 20 MiB), and the refusals are the core's typed reasons, so this file is
/// only the copy for each one.

/// One file the user picked, before it goes anywhere.
struct PickedAttachment: Equatable {
    let name: String
    let mimeType: String
    let data: Data
    /// A local file URL for a preview; nil for bytes that came from a picker without a file.
    let url: URL?

    var size: Int { data.count }
}

enum AttachmentKind: Equatable {
    case image
    case video
    case file
    case audio

    init(mimeType: String) {
        if mimeType.hasPrefix("image/") {
            self = .image
        } else if mimeType.hasPrefix("video/") {
            self = .video
        } else if mimeType.hasPrefix("audio/") {
            self = .audio
        } else {
            self = .file
        }
    }

    /// The letter on a file card's face: a type, not an illustration (design, `.file-card`).
    var face: String {
        switch self {
        case .image: "IMG"
        case .video: "VID"
        case .audio: "AUD"
        case .file: "FILE"
        }
    }
}

/// One upload as a message names it (C6.6): the gateway's reference and the file's name, never
/// the bytes. The composer hands these to the send, so what it draws is what goes out.
struct SentAttachment: Equatable {
    let reference: String
    let name: String?
}

/// One attachment in the composer, as a card.
struct PendingAttachment: Identifiable, Equatable {
    let id: String
    let name: String
    let mimeType: String
    let size: Int
    let kind: AttachmentKind
    /// The gateway's reference, once the upload came back. Nil means still in flight.
    var reference: String?
    var url: URL?

    var sizeLabel: String {
        if size >= 1024 * 1024 { return String(format: "%.1f MB", Double(size) / (1024 * 1024)) }
        if size >= 1024 { return "\(size / 1024) KB" }
        return "\(size) B"
    }
}

/// The gateway's own limits, as the composer validates and words them.
struct UploadLimits: Equatable {
    let maxCount: Int
    let maxFileBytes: Int
    let maxTotalBytes: Int

    static let fallback = UploadLimits(maxCount: 4, maxFileBytes: 6 * 1024 * 1024, maxTotalBytes: 24 * 1024 * 1024)
    /// A video has its own ceiling instead of the 6 MiB file one (`validate_upload_batch`).
    static let maxVideoBytes = 20 * 1024 * 1024
    static let maxVideos = 1

    /// The attach sheet's second line, which is the real limit rather than a description of it.
    static var attachSheetSubtitle: String {
        "Up to \(fallback.maxCount) files, \(megabytes(fallback.maxFileBytes)) each, 1 video"
    }

    static func megabytes(_ bytes: Int) -> String { "\(bytes / (1024 * 1024)) MiB" }
}

/// Why an attachment was refused, in Swift's own words. The adapter maps the core's typed
/// `AttachmentRejection` onto these, so the sentences live with the screen.
enum AttachmentReject: Equatable {
    case empty
    case tooMany
    case fileTooLarge
    case totalTooLarge
    case tooManyVideos
    case videoTooLarge
    case unsupported

    func copy(limits: UploadLimits) -> String {
        switch self {
        case .empty:
            "That file is empty."
        case .tooMany:
            "Up to \(limits.maxCount) files per message."
        case .fileTooLarge:
            "Each file is capped at \(UploadLimits.megabytes(limits.maxFileBytes))."
        case .totalTooLarge:
            "The whole message is capped at \(UploadLimits.megabytes(limits.maxTotalBytes))."
        case .tooManyVideos:
            "One video per message."
        case .videoTooLarge:
            "A video is capped at \(UploadLimits.megabytes(UploadLimits.maxVideoBytes))."
        case .unsupported:
            "That file type is not one the gateway accepts."
        }
    }
}

/// What one upload came back as: a reference to put on the message, a typed refusal, or a
/// transport failure.
enum AttachmentOutcome: Equatable {
    case uploaded(reference: String)
    case rejected(AttachmentReject)
    case failed
}

/// What the composer needs for attachments: one command, over a `ChatHandle.upload`.
@MainActor
protocol AttachmentDriving: AnyObject {
    func upload(name: String, mimeType: String, data: Data) async -> AttachmentOutcome
}

@MainActor
final class NoopAttachments: AttachmentDriving {
    func upload(name: String, mimeType: String, data: Data) async -> AttachmentOutcome { .failed }
}

// MARK: - voice

/// The two bounds the gateway enforces (`transcription_ws.py`).
enum VoiceLimits {
    static let floorMs = 650
    static let ceilingMs = 120_000
}

/// One recording, as the recorder hands it over. The duration is not in here: the gesture's own
/// clock is the single number the floor, the ceiling and the frame all use.
struct RecordedAudio: Equatable {
    let data: Data
}

/// The microphone, as a seam: `AVAudioRecorder` in the app, a fake in a test.
@MainActor
protocol AudioRecording: AnyObject {
    func start() throws
    func stop() -> RecordedAudio?
    func cancel()
}

/// What one transcription came back as (C6.5).
enum VoiceOutcome: Equatable {
    case transcribed(String)
    case refused(String)
}

@MainActor
protocol VoiceDriving: AnyObject {
    func transcribe(pcm: Data, durationMs: Int) async -> VoiceOutcome
}

@MainActor
final class NoopVoice: VoiceDriving {
    func transcribe(pcm: Data, durationMs: Int) async -> VoiceOutcome { .refused("not connected") }
}
