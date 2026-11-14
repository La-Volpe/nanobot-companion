import Foundation
import Testing
@testable import iosApp

/// Attachments and voice (plan.md I6). The limits are the gateway's own numbers (4 files,
/// 6 MiB each, 24 MiB total, one video ≤ 20 MiB) and the refusals are the core's typed reasons;
/// this suite owns the copy each reason gets and the two voice gestures.
@MainActor
struct AttachmentViewModelTests {

    final class FakeUploader: AttachmentDriving {
        var uploads: [(name: String, mimeType: String, size: Int)] = []
        var outcome: AttachmentOutcome = .uploaded(reference: "ref-1")

        func upload(name: String, mimeType: String, data: Data) async -> AttachmentOutcome {
            uploads.append((name, mimeType, data.count))
            return outcome
        }
    }

    private let limits = UploadLimits(maxCount: 4, maxFileBytes: 6 * 1024 * 1024, maxTotalBytes: 24 * 1024 * 1024)

    private func model(_ uploader: FakeUploader) -> AttachmentViewModel {
        AttachmentViewModel(driver: uploader, limits: limits)
    }

    private func image(_ name: String = "IMG_1.png", bytes: Int = 1_000) -> PickedAttachment {
        PickedAttachment(name: name, mimeType: "image/png", data: Data(repeating: 0, count: bytes), url: nil)
    }

    private func video(bytes: Int = 2_000) -> PickedAttachment {
        PickedAttachment(name: "clip.mov", mimeType: "video/quicktime", data: Data(repeating: 0, count: bytes), url: nil)
    }

    private func document(_ name: String = "notes.md", bytes: Int = 500) -> PickedAttachment {
        PickedAttachment(name: name, mimeType: "text/markdown", data: Data(repeating: 0, count: bytes), url: nil)
    }

    @Test func aPickedFileBecomesACardOfItsKind() async {
        let uploader = FakeUploader()
        let model = model(uploader)

        await model.add(image("photo.png"))
        await model.add(video())
        await model.add(document())

        #expect(uploader.uploads.map(\.name) == ["photo.png", "clip.mov", "notes.md"])
        #expect(model.attachments.map(\.kind) == [.image, .video, .file])
        #expect(model.attachments.allSatisfy { $0.reference != nil }, "each card carries the gateway's reference")
        #expect(model.images.count == 1, "images go in the grid")
        #expect(model.videos.count == 1, "a video gets its own card")
        #expect(model.files.count == 1, "a document gets a file card")
    }

    /// Each typed reason gets one sentence, and the sentence names the number that refused it.
    @Test func aRefusalSaysWhichLimitItHit() async {
        let uploader = FakeUploader()
        let model = model(uploader)

        uploader.outcome = .rejected(.tooMany)
        await model.add(image())
        #expect(model.lastRejection == "Up to 4 files per message.")

        uploader.outcome = .rejected(.fileTooLarge)
        await model.add(image())
        #expect(model.lastRejection == "Each file is capped at 6 MiB.")

        uploader.outcome = .rejected(.totalTooLarge)
        await model.add(image())
        #expect(model.lastRejection == "The whole message is capped at 24 MiB.")

        uploader.outcome = .rejected(.tooManyVideos)
        await model.add(video())
        #expect(model.lastRejection == "One video per message.")

        uploader.outcome = .rejected(.videoTooLarge)
        await model.add(video())
        #expect(model.lastRejection == "A video is capped at 20 MiB.")

        uploader.outcome = .rejected(.unsupported)
        await model.add(document("archive.zip"))
        #expect(model.lastRejection == "That file type is not one the gateway accepts.")

        uploader.outcome = .rejected(.empty)
        await model.add(document("empty.md"))
        #expect(model.lastRejection == "That file is empty.")
    }

    @Test func aRefusedFileNeverBecomesACard() async {
        let uploader = FakeUploader()
        let model = model(uploader)
        uploader.outcome = .rejected(.fileTooLarge)

        await model.add(image("huge.png"))

        #expect(model.attachments.isEmpty, "a refused file stays out of the composer")
        #expect(model.lastRejection != nil)
    }

    /// P13's other half: a card is a promise about the chat it was picked in. The shell used to
    /// keep one card list across every chat, so a photo uploaded in one chat stayed on screen in
    /// the next — shown, sent nowhere, and cleared by that chat's own send.
    @Test func cardsBelongToTheChatTheyWerePickedIn() async {
        let uploader = FakeUploader()
        let model = AttachmentViewModel(driver: uploader, limits: limits)

        model.use(uploader, chatId: "chat-a")
        await model.add(image("in-a.png"))
        #expect(model.references == ["ref-1"])

        model.use(uploader, chatId: "chat-b")
        #expect(model.attachments.isEmpty, "the next chat does not inherit a card its send cannot carry")

        model.use(uploader, chatId: "chat-a")
        #expect(model.references == ["ref-1"], "and going back finds it, because the upload is still pending there")
    }

    @Test func removingACardTakesItBackOut() async {
        let uploader = FakeUploader()
        let model = model(uploader)
        await model.add(image("one.png"))
        await model.add(image("two.png"))

        model.remove(id: try! #require(model.attachments.first).id)

        #expect(model.attachments.map(\.name) == ["two.png"])
    }

    @Test func theLightboxOpensOnTheTappedImage() async {
        let uploader = FakeUploader()
        let model = model(uploader)
        await model.add(image("one.png"))
        await model.add(image("two.png"))

        model.openLightbox(at: 1)
        #expect(model.lightbox == 1, "the tapped image, not the first one")
        #expect(model.lightboxImage?.name == "two.png")

        model.closeLightbox()
        #expect(model.lightbox == nil)
    }

    @Test func theDesignsOwnCopyIsWhatTheSheetSays() {
        #expect(UploadLimits.attachSheetSubtitle == "Up to 4 files, 6 MiB each, 1 video",
                "the attach sheet's second line is the real limit, not a description of one")
    }
}

/// The two voice gestures (plan.md I6): hold to record with the gateway's 650 ms floor, or tap
/// to start dictating and tap again to stop. Both end at the gateway's 120 s ceiling.
@MainActor
struct VoiceViewModelTests {

    final class Recorder: AudioRecording {
        private(set) var starts = 0
        private(set) var stops = 0
        var cancelled = 0
        var data = Data(repeating: 1, count: 64)

        func start() throws { starts += 1 }
        func stop() -> RecordedAudio? {
            stops += 1
            return RecordedAudio(data: data)
        }
        func cancel() { cancelled += 1 }
    }

    final class Transcriber: VoiceDriving {
        var requests: [Int] = []
        var outcome: VoiceOutcome = .transcribed("hello from the gateway")

        func transcribe(pcm: Data, durationMs: Int) async -> VoiceOutcome {
            requests.append(durationMs)
            return outcome
        }
    }

    private func model(_ recorder: Recorder, _ transcriber: Transcriber) -> VoiceViewModel {
        VoiceViewModel(recording: recorder, driver: transcriber)
    }

    /// The plan's red line: a release under the gateway's floor is not sent anywhere.
    @Test func holdUnder650msCancels() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 1_000)
        await model.endHold(nowMs: 1_600)

        #expect(recorder.cancelled == 1, "600 ms is under the gateway's 650 ms floor")
        #expect(transcriber.requests.isEmpty, "nothing is sent for a tap that never became audio")
        #expect(model.notice == "Hold a little longer to record voice.")
        #expect(model.phase == .idle)
    }

    @Test func aHoldOverTheFloorIsTranscribed() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 0)
        await model.endHold(nowMs: 1_400)

        #expect(transcriber.requests == [1_400], "the duration the gateway validates")
        #expect(model.lastTranscript == "hello from the gateway")
        #expect(model.phase == .idle)
    }

    /// The plan's second red line: a tap starts dictating and the next tap stops it, once.
    @Test func tapModeTogglesOnce() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        await model.tap(nowMs: 1_000)
        #expect(model.phase == .recording)
        #expect(recorder.starts == 1)

        await model.tap(nowMs: 2_000)
        #expect(model.phase == .idle, "the second tap stops the dictation")
        #expect(recorder.stops == 1)
        #expect(transcriber.requests == [1_000])
        #expect(recorder.starts == 1, "one gesture, one recording")
    }

    /// A transcription that is still in flight is a visible state, not a silent wait: the field
    /// says "Transcribing…" until the gateway answers.
    final class GatedTranscriber: VoiceDriving {
        let gate = AsyncGate()
        var outcome: VoiceOutcome = .transcribed("late text")

        func transcribe(pcm: Data, durationMs: Int) async -> VoiceOutcome {
            await gate.wait()
            return outcome
        }
    }

    @Test func transcribingIsAVisibleStateBeforeTheTextArrives() async {
        let recorder = Recorder()
        let transcriber = GatedTranscriber()
        let model = VoiceViewModel(recording: recorder, driver: transcriber)

        model.beginHold(nowMs: 0)
        let task = Task { await model.endHold(nowMs: 900) }
        await waitUntil { model.phase == .transcribing }
        #expect(model.phase == .transcribing, "the field says Transcribing… while the gateway works")
        #expect(model.transcribing)

        await transcriber.gate.open()
        await task.value
        #expect(model.phase == .idle)
        #expect(model.lastTranscript == "late text")
    }

    @Test func aRefusedTranscriptionSaysWhy() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        transcriber.outcome = .refused("transcription_unavailable")
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 0)
        await model.endHold(nowMs: 900)

        #expect(model.notice == "The gateway could not transcribe that recording.")
        #expect(model.lastTranscript == nil)
        #expect(model.phase == .idle)
    }

    @Test func aRecordingOverTwoMinutesIsRefused() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 0)
        model.tick(nowMs: 120_500)

        #expect(model.notice == "Recording is too long.", "the gateway's own 120 s ceiling")
        #expect(transcriber.requests.isEmpty)
        #expect(recorder.cancelled == 1)
    }

    @Test func theElapsedClockCountsWhileRecording() {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 1_000)
        model.tick(nowMs: 3_500)

        #expect(model.elapsedMs == 2_500, "the meter's clock, from the injected times")
        #expect(model.elapsedLabel == "0:02")
    }
}

/// A gate a test can open, so a view model's in-flight state can be observed instead of raced.
@MainActor
final class AsyncGate {
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var opened = false

    func open() async {
        opened = true
        let pending = waiters
        waiters = []
        pending.forEach { $0.resume() }
    }

    func wait() async {
        if opened { return }
        await withCheckedContinuation { waiters.append($0) }
    }
}
