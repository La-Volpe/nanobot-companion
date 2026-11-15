#if DEBUG
import Foundation
import UIKit

/// A chat that answers without a gateway, for the flows a tap can prove and for the debug menu
/// G2 asks for (plan.md I8). It streams the way the real one does — a pending prompt, an
/// accepted one, then answer chunks — so the screen's states are the same states.
@MainActor
final class ScriptedChat: ChatDriving {
    private let onChange: (ChatThreadState) -> Void
    private var turns: [ChatTurnState] = []
    private var stopRequested = false
    private(set) var opens = 0

    init(onChange: @escaping (ChatThreadState) -> Void) {
        self.onChange = onChange
    }

    func open() async {
        opens += 1
        turns = [ChatTurnState(id: "history-1", prompt: "earlier question", promptPending: false,
                               answer: "an earlier answer", answerStreaming: false, running: false,
                               activity: Self.historyActivity,
                               startedAtMs: Int64(Date().timeIntervalSince1970 * 1000) - 8_000)]
        onChange(ChatThreadState(turns: turns))
    }

    /// The activity the scripted history carries, so the cluster is on screen in a flow test and
    /// in the debug menu without a gateway (shaped like a recorded `file` turn).
    private static let historyActivity: [ChatActivityItem] = [
        ChatActivityItem(id: "r1", kind: .reasoning(text: "The name has to come from the real file list.", done: true)),
        ChatActivityItem(id: "tool:call_1", kind: .tool(name: "run_command", summary: "ls", status: .done, detail: "18 ms")),
        ChatActivityItem(
            id: "file:call_2",
            kind: .fileEdit(
                FileEditState(
                    path: "tool-probe.txt",
                    status: .done,
                    added: 1,
                    deleted: 1,
                    operation: "edit",
                    diff: "--- tool-probe.txt\n+++ tool-probe.txt\n@@ -1 +1 @@\n-old name\n+nanobot"
                )
            )
        ),
    ]

    func send(_ text: String) async -> String? {
        let id = "turn-\(turns.count + 1)"
        stopRequested = false
        turns.append(ChatTurnState(id: id, prompt: text, promptPending: true, answer: "", answerStreaming: true, running: true))
        onChange(ChatThreadState(turns: turns))

        try? await Task.sleep(nanoseconds: 400_000_000)
        update(id) { turn in
            ChatTurnState(id: turn.id, prompt: turn.prompt, promptPending: false, answer: "", answerStreaming: true, running: true)
        }

        update(id) { turn in
            ChatTurnState(id: turn.id, prompt: turn.prompt, promptPending: false, answer: "", answerStreaming: true, running: true,
                          activity: turn.activity + [
                              ChatActivityItem(id: "scripted-reasoning", kind: .reasoning(text: "Reading the thread first.", done: false)),
                              ChatActivityItem(id: "tool:scripted-1", kind: .tool(name: "read_file", summary: "README.md", status: .running, detail: nil)),
                          ],
                          startedAtMs: turn.startedAtMs)
        }

        // The answer carries markdown, so the flow test draws prose, a list and a fence the way
        // the core parsed them.
        // Long enough that a flow test can type while it runs, and markdown-shaped so the
        // prose, the bullets and the fence are what the parser produced.
        let chunks = ["Streaming ", "an answer ", "with **markdown**:\n\n- one bullet\n- two bullets\n\n```swift\nlet ready = true\nprint(ready)\n```"]
        for chunk in chunks {
            if stopRequested { break }
            try? await Task.sleep(nanoseconds: 1_400_000_000)
            update(id) { turn in
                ChatTurnState(id: turn.id, prompt: turn.prompt, promptPending: false,
                              answer: turn.answer + chunk, answerStreaming: true, running: true)
            }
        }
        update(id) { turn in
            ChatTurnState(id: turn.id, prompt: turn.prompt, promptPending: false,
                          answer: turn.answer, answerStreaming: false, running: false)
        }
        return id
    }

    func stop() async {
        stopRequested = true
        if let last = turns.last {
            update(last.id) { turn in
                ChatTurnState(id: turn.id, prompt: turn.prompt, promptPending: false,
                              answer: turn.answer + " (stopped)", answerStreaming: false, running: false)
            }
        }
    }

    private func update(_ id: String, _ change: (ChatTurnState) -> ChatTurnState) {
        guard let index = turns.firstIndex(where: { $0.id == id }) else { return }
        turns[index] = change(turns[index])
        onChange(ChatThreadState(turns: turns))
    }
}

/// The scripted chat as the composer's driver, so the primary button, the queue chip and the
/// sheets behave in a flow test the way they do against a gateway.
@MainActor
final class ScriptedComposer: ComposerDriving {
    private let chat: ScriptedChat
    private var queued: String?

    init(chat: ScriptedChat) {
        self.chat = chat
    }

    func sendPrompt(_ text: String, media: [SentAttachment]) async -> Bool {
        await chat.send(text) != nil
    }

    func enqueue(_ text: String) {
        queued = [queued, text].compactMap { $0 }.joined(separator: "\n")
    }

    func stop() async {
        await chat.stop()
    }

    func sendQueuedNow() async {
        guard let text = queued else { return }
        queued = nil
        _ = await chat.send(text)
    }

    func discardQueued() {
        queued = nil
    }

    func setModelPreset(_ preset: String) async {}

    func setWorkspaceScope(_ access: WorkspaceAccess) async {}

    /// A canned palette for the flow test: the sheet's layout, its insertion and its dismissal
    /// are what a tap proves, while which rows the gateway sends is `PaletteTest`'s job.
    func loadPalette(text: String, cursor: Int) async -> PaletteSheet? {
        guard text.hasPrefix("/") else { return nil }
        return PaletteSheet(
            mode: .slash,
            title: "Commands",
            rows: [
                PaletteRow(id: "c1", label: "/stop", subtitle: "Stop the running turn", group: "Commands", insert: "/stop "),
                PaletteRow(id: "c2", label: "/model", subtitle: "Switch the model preset", group: "Commands", insert: "/model "),
            ]
        )
    }
}

/// An uploader that answers like the gateway does, so the attach flow's cards, lightbox and
/// refusals are provable without a gateway (and without the system photo picker).
@MainActor
final class ScriptedUploader: AttachmentDriving {
    private var uploads = 0
    /// One named file fails its first upload and lands on the second, so a flow test can see the
    /// card that keeps a reason and a Retry *and* watch the retry change it — without cutting
    /// the network.
    private let failingName: String?
    private var failedOnce = false

    init(failingName: String? = nil) {
        self.failingName = failingName
    }

    func upload(name: String, mimeType: String, data: Data) async -> AttachmentOutcome {
        uploads += 1
        if name == failingName, !failedOnce {
            failedOnce = true
            return .failed
        }
        // One small PNG stands in for every pick: the point is the card, not the image.
        return .uploaded(reference: "scripted-ref-\(uploads)")
    }

    /// The two picks the seeded composer opens with: a photo that landed, and a document whose
    /// upload did not.
    static var seededPicks: [(name: String, mimeType: String, data: Data)] {
        [
            ("IMG_2841.jpg", "image/jpeg", demoImage()),
            ("attachment_policy.py", "text/plain", Data("print('policy')\n".utf8)),
        ]
    }

    /// A drawn picture, not a blank one: a card with no preview would not prove the preview.
    private static func demoImage() -> Data {
        let size = CGSize(width: 240, height: 160)
        let image = UIGraphicsImageRenderer(size: size).image { context in
            UIColor.systemOrange.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor.black.setFill()
            context.fill(CGRect(x: 40, y: 60, width: 160, height: 40))
        }
        return image.jpegData(compressionQuality: 0.8) ?? Data()
    }
}

/// A recorder with no microphone behind it: canned WAV bytes, so a flow test can hold the
/// button and watch the states without a permission alert.
@MainActor
final class ScriptedRecorder: AudioRecording {
    func start() throws {}
    func stop() -> RecordedAudio? { RecordedAudio(data: Data(repeating: 0, count: 44)) }
    func cancel() {}
}

/// A transcriber that answers the way the gateway does, after a visible moment of "Transcribing…".
@MainActor
final class ScriptedVoice: VoiceDriving {
    func transcribe(pcm: Data, durationMs: Int) async -> VoiceOutcome {
        try? await Task.sleep(nanoseconds: 600_000_000)
        return .transcribed("Check the attachment policy limits.")
    }
}
#endif
