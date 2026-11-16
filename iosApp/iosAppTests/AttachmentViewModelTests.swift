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

    // MARK: - the attach sheet's hand-off (T50)

    /// A row's tap says what to open and waits. iOS will not present a second sheet while the
    /// composer's sheet is still up, so the request made in the same tap was the tap that did
    /// nothing until the overlay was closed by hand.
    @Test func theHandoffCarriesTheRowThatWasTapped() {
        let model = model(FakeUploader())

        #expect(model.takeHandoff(cameraRefusal: nil) == nil, "nothing asked, nothing opens")

        model.request(.photos)
        #expect(model.takeHandoff(cameraRefusal: nil) == .photos)

        model.request(.files)
        #expect(model.takeHandoff(cameraRefusal: nil) == .files)

        model.request(.camera)
        #expect(model.takeHandoff(cameraRefusal: nil) == .camera)
    }

    /// One dismissal spends one request: the next dismissal of a sheet nobody asked from opens
    /// nothing, which is what a swipe down must do.
    @Test func aSpentHandoffOpensNothing() {
        let model = model(FakeUploader())

        model.request(.files)
        _ = model.takeHandoff(cameraRefusal: nil)

        #expect(model.takeHandoff(cameraRefusal: nil) == nil)
    }

    /// A camera that is off in Settings is a sentence on the composer, never a black cover — and
    /// the sentence is a parameter, because `AVFoundation` is the screen's business, not this
    /// model's.
    @Test func aRefusedCameraHandsOnTheReasonInsteadOfTheCamera() {
        let model = model(FakeUploader())
        let sentence = "The camera is off for Nanobot. Turn it on in Settings."

        model.request(.camera)

        #expect(model.takeHandoff(cameraRefusal: sentence) == .refused(sentence))
    }
}

/// Hold to talk (T48, the iOS twin of `VoiceViewModelTest`): the press records the instant it
/// lands, a release under 800 ms is a discard that asks the gateway nothing, a release at or above
/// it transcribes, and a drag up locks the recording so Send and Cancel own it instead of the
/// finger.
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

    private func model(
        _ recorder: Recorder,
        _ transcriber: VoiceDriving,
        hints: VoiceHints = InMemoryVoiceHints()
    ) -> VoiceViewModel {
        VoiceViewModel(recording: recorder, driver: transcriber, hints: hints)
    }

    /// The owner's lag, pinned: nothing sits between the finger and the microphone.
    @Test func recordingStartsOnTouchDown() {
        let recorder = Recorder()
        let model = model(recorder, Transcriber())

        model.beginHold(nowMs: 1_000)

        #expect(model.phase == .recording, "the finger is the start")
        #expect(model.recordingNow)
        #expect(recorder.starts == 1, "the microphone is open before the finger moves")
        #expect(model.elapsedMs == 0)
    }

    /// T48's floor: 799 ms is a tap, so nothing at all goes to the gateway.
    @Test func aReleaseUnder800msSendsNothing() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 1_000)
        await model.release(nowMs: 1_799)

        #expect(transcriber.requests.isEmpty, "799 ms is a tap, and a tap never reaches the gateway")
        #expect(recorder.cancelled == 1, "the recording is thrown away, not stopped and kept")
        #expect(recorder.stops == 0)
        #expect(model.phase == .idle)
    }

    @Test func aReleaseOf800msIsTranscribed() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 1_000)
        await model.release(nowMs: 1_800)

        #expect(transcriber.requests == [800], "the gesture's own clock is the duration asked about")
        #expect(model.lastTranscript == "hello from the gateway")
        #expect(model.phase == .idle)
    }

    /// The phone's floor is above the gateway's, so a recording it would refuse is never asked.
    @Test func theDiscardFloorSitsAboveTheGateways() {
        #expect(VoiceLimits.discardMs > VoiceLimits.floorMs)
    }

    /// A tap explains the gesture once and never again — Android's "tapping does nothing" answered
    /// with a sentence instead of a second recording mode.
    @Test func aTapSaysHoldToTalkOnce() async {
        let hints = InMemoryVoiceHints()
        let first = model(Recorder(), Transcriber(), hints: hints)

        first.beginHold(nowMs: 0)
        await first.release(nowMs: 100)
        #expect(first.notice == "Hold to talk")

        let second = model(Recorder(), Transcriber(), hints: hints)
        second.beginHold(nowMs: 0)
        await second.release(nowMs: 100)
        #expect(second.notice == nil, "the hint is remembered, so the second tap gets no nagging")
        #expect(second.phase == .idle)
    }

    /// Drag up: the finger may leave the screen, and a lift is not a stop.
    @Test func aDragUpLocksAndSendFinishes() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 0)
        model.lock()
        await model.release(nowMs: 3_000)

        #expect(model.locked)
        #expect(model.phase == .recording, "a locked recording outlives its gesture")
        #expect(transcriber.requests.isEmpty)

        await model.sendLocked(nowMs: 3_000)

        #expect(transcriber.requests == [3_000], "Send is what finishes a locked recording")
        #expect(model.lastTranscript == "hello from the gateway")
        #expect(model.phase == .idle)
        #expect(model.locked == false)
    }

    /// The cancel target throws the recording away: no request, no sentence, nothing to dismiss.
    @Test func theCancelTargetSendsNothing() async {
        let recorder = Recorder()
        let transcriber = Transcriber()
        let model = model(recorder, transcriber)

        model.beginHold(nowMs: 0)
        model.lock()
        model.cancelRecording()

        #expect(model.phase == .idle)
        #expect(recorder.cancelled == 1)
        #expect(recorder.stops == 0)
        #expect(transcriber.requests.isEmpty, "a cancel asks the gateway nothing")
        #expect(model.notice == nil, "and it is not a failure, so it says nothing")
    }

    /// The transcribing arc advances one step per tick of the screen's clock, never on an
    /// animation of its own, and it stays a bounded angle.
    @Test func theArcAdvancesOnTicksAndStaysBounded() async {
        let transcriber = GatedTranscriber()
        let model = model(Recorder(), transcriber)

        model.beginHold(nowMs: 0)
        let task = Task { await model.release(nowMs: 2_000) }
        await waitUntil { model.phase == .transcribing }

        model.tick(nowMs: 2_100)
        let first = model.sweepDegrees
        model.tick(nowMs: 2_200)
        #expect(model.sweepDegrees != first, "the arc moves on a tick, not on a frame")
        #expect(model.elapsedMs == 0, "and the meter's clock is not the arc's")

        for _ in 0 ..< 40 { model.tick(nowMs: 3_000) }
        #expect(model.sweepDegrees >= 0 && model.sweepDegrees < 360, "a bounded angle, not a runaway")

        await transcriber.gate.open()
        await task.value
        #expect(model.phase == .idle)
        #expect(model.sweepDegrees == 0, "the arc goes away with the wait")
    }

    /// A press while the machine is busy is not a second gesture: one press, one recording.
    @Test func aSecondPressDoesNotOpenASecondRecording() {
        let recorder = Recorder()
        let model = model(recorder, Transcriber())

        model.beginHold(nowMs: 0)
        model.beginHold(nowMs: 500)

        #expect(recorder.starts == 1, "the recorder answers the finger that started it")
    }

    /// A transcription that is still in flight is a visible state, not a silent wait.
    final class GatedTranscriber: VoiceDriving {
        let gate = AsyncGate()
        var outcome: VoiceOutcome = .transcribed("late text")

        func transcribe(pcm: Data, durationMs: Int) async -> VoiceOutcome {
            await gate.wait()
            return outcome
        }
    }

    @Test func transcribingIsAVisibleStateBeforeTheTextArrives() async {
        let transcriber = GatedTranscriber()
        let model = model(Recorder(), transcriber)

        model.beginHold(nowMs: 0)
        let task = Task { await model.release(nowMs: 900) }
        await waitUntil { model.phase == .transcribing }
        #expect(model.transcribing, "the button's arc and Transcribing… are states, not gaps")

        await transcriber.gate.open()
        await task.value
        #expect(model.phase == .idle)
        #expect(model.lastTranscript == "late text")
    }

    @Test func aRefusedTranscriptionSaysWhy() async {
        let transcriber = Transcriber()
        transcriber.outcome = .refused("not_configured")
        let model = model(Recorder(), transcriber)

        model.beginHold(nowMs: 0)
        await model.release(nowMs: 900)

        #expect(model.notice == "The gateway could not transcribe that recording.")
        #expect(model.lastTranscript == nil)
        #expect(model.phase == .idle)
    }

    @Test func aRecordingOverTwoMinutesIsRefused() {
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
        let model = model(Recorder(), Transcriber())

        model.beginHold(nowMs: 1_000)
        model.tick(nowMs: 3_500)

        #expect(model.elapsedMs == 2_500, "the meter's clock, from the injected times")
        #expect(model.elapsedLabel == "0:02", "the timer the owner could not see")
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
