import AVFoundation
import Foundation

/// The microphone, really: `AVAudioRecorder` into a 16 kHz mono WAV, which is what the gateway's
/// `transcribe_audio` takes (`data:audio/wav;base64,…`, C6.5).
///
/// A simulator has no microphone, so a recording there is silence — the transcription path
/// itself is real bytes through the real gateway either way.
@MainActor
final class MicrophoneRecorder: AudioRecording {

    private var recorder: AVAudioRecorder?
    private var url: URL?

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice-\(UUID().uuidString).wav")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
        ]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.isMeteringEnabled = true
        guard recorder.record() else { throw RecorderError.wouldNotStart }
        self.recorder = recorder
        self.url = url
    }

    func stop() -> RecordedAudio? {
        guard let recorder, let url else { return nil }
        recorder.stop()
        self.recorder = nil
        self.url = nil
        try? AVAudioSession.sharedInstance().setActive(false)
        defer { try? FileManager.default.removeItem(at: url) }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return RecordedAudio(data: data)
    }

    func cancel() {
        recorder?.stop()
        recorder?.deleteRecording()
        recorder = nil
        url = nil
        try? AVAudioSession.sharedInstance().setActive(false)
    }

    enum RecorderError: Error {
        case wouldNotStart
    }
}
