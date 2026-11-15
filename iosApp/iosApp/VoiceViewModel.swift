import Foundation

/// Hold-to-record and tap-to-dictate (plan.md I6, C6.5).
///
/// Two gestures, one machine. A hold records until release and is refused under the gateway's
/// own 650 ms floor; a tap starts dictating and the next tap stops it. Both end at the 120 s
/// ceiling, and between release and the transcript the field says "Transcribing…", because that
/// wait is a state the user has to see.
@MainActor
final class VoiceViewModel: ObservableObject {

    enum Phase: Equatable {
        case idle
        case recording
        case transcribing
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var elapsedMs: Int = 0
    /// The one sentence a gesture can produce: why it stopped, or why it was refused.
    @Published private(set) var notice: String?
    @Published private(set) var lastTranscript: String?

    /// True while the current recording came from a tap rather than a hold.
    private(set) var tapMode = false

    private var recording: AudioRecording
    private var driver: VoiceDriving
    private var startedAtMs: Int = 0

    init(recording: AudioRecording, driver: VoiceDriving) {
        self.recording = recording
        self.driver = driver
    }

    /// The screen swaps in the chat once a gateway is open, and a scripted recorder in a flow
    /// test — a simulator has no microphone, and the permission prompt would block the test.
    func use(_ driver: VoiceDriving, recording: AudioRecording? = nil) {
        self.driver = driver
        if let recording { self.recording = recording }
    }

    var transcribing: Bool { phase == .transcribing }

    /// `0:07`, which is the meter's clock.
    var elapsedLabel: String {
        let seconds = elapsedMs / 1000
        return "\(seconds / 60):" + String(format: "%02d", seconds % 60)
    }

    /// The transcript is appended to whatever was typed, by the core (C6.5); the screen reads it
    /// from here once it arrives.
    private(set) var pendingTranscript: String?

    // MARK: - the hold gesture

    func beginHold(nowMs: Int) {
        guard phase == .idle else { return }
        start(at: nowMs, tap: false)
    }

    func endHold(nowMs: Int) async {
        // A hold that was actually a tap keeps dictating; that is what makes tap-to-dictate
        // work on a short press without a second gesture.
        guard phase == .recording, !tapMode else { return }
        let held = nowMs - startedAtMs
        if held < VoiceLimits.floorMs {
            cancel(notice: "Hold a little longer to record voice.")
            return
        }
        await finish(at: nowMs)
    }

    // MARK: - the tap gesture

    func tap(nowMs: Int) async {
        switch phase {
        case .idle:
            start(at: nowMs, tap: true)
        case .recording:
            if tapMode {
                await finish(at: nowMs)
            } else {
                // A hold in progress that the user tapped at: the tap ends it like a release.
                await endHold(nowMs: nowMs)
            }
        case .transcribing:
            break
        }
    }

    /// A press shorter than the tap threshold arms tap-to-dictate and keeps recording, which is
    /// the artifact's rule (`if (Date.now() - holdStart < 300) { tapMode = true; return; }`).
    func armTapMode() {
        guard phase == .recording else { return }
        tapMode = true
    }

    /// A notice is the reader's to put away. It is a line on the composer, not an alert, so the
    /// control that clears it is the reader's only way out (T31: the owner's was undismissable,
    /// and a gateway with no speech provider produces one for everyone).
    func dismissNotice() {
        notice = nil
    }

    /// Called by the meter's timer; the model owns no clock of its own, so the view passes time.
    func tick(nowMs: Int) {
        guard phase == .recording else { return }
        elapsedMs = nowMs - startedAtMs
        if elapsedMs > VoiceLimits.ceilingMs {
            cancel(notice: "Recording is too long.")
        }
    }

    // MARK: - internals

    private func start(at nowMs: Int, tap: Bool) {
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
        tapMode = tap
        phase = .recording
    }

    private func finish(at nowMs: Int) async {
        guard let audio = recording.stop() else {
            cancel(notice: "That recording did not reach the microphone.")
            return
        }
        let durationMs = nowMs - startedAtMs
        phase = .transcribing
        let outcome = await driver.transcribe(pcm: audio.data, durationMs: durationMs)
        phase = .idle
        tapMode = false
        elapsedMs = 0
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
        phase = .idle
        tapMode = false
        elapsedMs = 0
        notice = message
    }
}
