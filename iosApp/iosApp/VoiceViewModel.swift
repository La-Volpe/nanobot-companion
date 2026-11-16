import Foundation

/// Hold to talk (T48).
///
/// The model is one machine with three states and one rule of thumb: the finger is the start. A
/// press records the instant it lands — no threshold, no commit step — because the old 300 ms
/// tap-mode wait read as lag on the owner's phone. What a release then means depends only on how
/// long the press lasted: under `VoiceLimits.discardMs` it was a tap, the recording is thrown away
/// with no request, and the first tap explains the gesture; at or above it the recording goes to
/// the gateway and the transcript lands in the field. A drag up in between locks the recording so
/// the finger may leave the screen: the button becomes Send, and a cancel target throws the
/// recording away.
///
/// The clock is the caller's: the gesture passes `nowMs`, so the floor, the ceiling and the frame's
/// duration are one number and no timer runs inside the model. That includes the transcribing arc —
/// it advances on `tick`, never on an animation, because XCUITest waits for quiescence and an
/// endless animation never gets there.
@MainActor
final class VoiceViewModel: ObservableObject {

    enum Phase: Hashable {
        case idle
        case recording
        case transcribing
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var elapsedMs: Int = 0
    /// True once a drag up handed the recording to the button; the finger may leave the screen.
    @Published private(set) var locked = false
    /// The one sentence a gesture can produce: why it stopped, or why it was refused.
    @Published private(set) var notice: String?
    @Published private(set) var lastTranscript: String?
    /// The transcribing arc's angle. It moves only on `tick`, one step per tick: a determinate
    /// sweep, not an animation.
    @Published private(set) var sweepDegrees: Double = 0

    private var recording: AudioRecording
    private var driver: VoiceDriving
    private var hints: VoiceHints
    private var startedAtMs: Int = 0

    init(recording: AudioRecording, driver: VoiceDriving, hints: VoiceHints = InMemoryVoiceHints()) {
        self.recording = recording
        self.driver = driver
        self.hints = hints
    }

    /// The screen swaps in the chat once a gateway is open, and a scripted recorder in a flow
    /// test — a simulator has no microphone, and the permission prompt would block the test.
    func use(_ driver: VoiceDriving, recording: AudioRecording? = nil, hints: VoiceHints? = nil) {
        self.driver = driver
        if let recording { self.recording = recording }
        if let hints { self.hints = hints }
    }

    var transcribing: Bool { phase == .transcribing }

    var recordingNow: Bool { phase == .recording }

    /// `0:07`, which is the meter's clock.
    var elapsedLabel: String {
        let seconds = elapsedMs / 1000
        return "\(seconds / 60):" + String(format: "%02d", seconds % 60)
    }

    /// The transcript the screen appends to the field once it arrives (the composer is never sent
    /// on the reader's behalf).
    private(set) var pendingTranscript: String?

    /// A notice is the reader's to put away. It is a line on the composer, not an alert, so the
    /// control that clears it is the reader's only way out (T31).
    func dismissNotice() {
        notice = nil
    }

    // MARK: - the gesture

    /// The finger landed: record now. A second press while a recording is in flight is not a
    /// gesture the machine has, so it is ignored rather than allowed to steal the first one.
    func beginHold(nowMs: Int) {
        guard phase == .idle else { return }
        notice = nil
        lastTranscript = nil
        do {
            try recording.start()
        } catch {
            notice = "The microphone is not available."
            return
        }
        startedAtMs = nowMs
        elapsedMs = 0
        locked = false
        phase = .recording
    }

    /// The finger lifted. Under the discard floor the press was a tap: the recording is thrown away
    /// and the gateway is never asked. At or above it, the recording is the question.
    func release(nowMs: Int) async {
        guard phase == .recording else { return }
        // A locked recording outlived its gesture: the button owns it now, and a lift is not a stop.
        guard !locked else { return }
        if nowMs - startedAtMs < VoiceLimits.discardMs {
            discardAsTap()
            return
        }
        await finish(at: nowMs)
    }

    /// A drag up: the recording carries on with no finger on it, and the button becomes Send.
    func lock() {
        guard phase == .recording else { return }
        locked = true
    }

    /// The Send side of a locked recording: tap it to finish and transcribe.
    func sendLocked(nowMs: Int) async {
        guard phase == .recording, locked else { return }
        await finish(at: nowMs)
    }

    /// The cancel target: the recording is thrown away without a request and without a sentence.
    func cancelRecording() {
        guard phase == .recording else { return }
        recording.cancel()
        reset()
    }

    /// Called by the screen's ticker; the model owns no clock of its own, so the view passes time.
    func tick(nowMs: Int) {
        switch phase {
        case .recording:
            elapsedMs = nowMs - startedAtMs
            if elapsedMs > VoiceLimits.ceilingMs {
                cancel(notice: "Recording is too long.")
            }
        case .transcribing:
            sweepDegrees = (sweepDegrees + Self.sweepStepDegrees)
                .truncatingRemainder(dividingBy: 360)
        case .idle:
            break
        }
    }

    // MARK: - internals

    /// A tap is discarded silently, and the first one explains the gesture — once ever, because a
    /// hint that keeps coming back is a nag. Persisting the flag is `VoiceHints`'s job.
    private func discardAsTap() {
        recording.cancel()
        reset()
        if !hints.hasSeenHoldHint() {
            hints.markHoldHintSeen()
            notice = "Hold to talk"
        }
    }

    private func finish(at nowMs: Int) async {
        let audio = recording.stop()
        let durationMs = nowMs - startedAtMs
        reset()
        guard let audio, !audio.data.isEmpty else {
            notice = "That recording did not reach the microphone."
            return
        }
        phase = .transcribing
        // The transcript arrives on the frame's own request id, so the wait is a state the screen
        // shows: the button is inactive and its arc circles while we ask.
        let outcome = await driver.transcribe(pcm: audio.data, durationMs: durationMs)
        phase = .idle
        sweepDegrees = 0
        switch outcome {
        case let .transcribed(text):
            pendingTranscript = text
            lastTranscript = text
            notice = nil
        case let .refused(detail):
            // The gateway's own word for it is not a sentence; the screen says what happened.
            _ = detail
            notice = "The gateway could not transcribe that recording."
        }
    }

    private func cancel(notice message: String) {
        recording.cancel()
        reset()
        notice = message
    }

    private func reset() {
        phase = .idle
        locked = false
        elapsedMs = 0
        sweepDegrees = 0
    }

    /// 12° a tick at 100 ms is a light circle, not a spinner.
    private static let sweepStepDegrees: Double = 12
}
