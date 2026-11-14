#if DEBUG
import Foundation

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
                               answer: "an earlier answer", answerStreaming: false, running: false)]
        onChange(ChatThreadState(turns: turns))
    }

    func send(_ text: String) async -> String? {
        let id = "turn-\(turns.count + 1)"
        stopRequested = false
        turns.append(ChatTurnState(id: id, prompt: text, promptPending: true, answer: "", answerStreaming: true, running: true))
        onChange(ChatThreadState(turns: turns))

        try? await Task.sleep(nanoseconds: 400_000_000)
        update(id) { turn in
            ChatTurnState(id: turn.id, prompt: turn.prompt, promptPending: false, answer: "", answerStreaming: true, running: true)
        }

        for chunk in ["Streaming ", "an answer ", "chunk by chunk."] {
            if stopRequested { break }
            try? await Task.sleep(nanoseconds: 700_000_000)
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
#endif
