import Foundation

/// The thread as a Swift view model can reason about it: plain values, no framework types.
/// The adapter layer maps `core.ThreadState` onto this (plan.md D9: one reducer fills it,
/// whether the frames came from the live socket or from a replayed history).
struct ChatThreadState: Equatable {
    var turns: [ChatTurnState] = []

    /// Rows in reading order: the prompt, then the answer once there is one to show.
    var rows: [ChatRow] {
        turns.flatMap { turn in
            var rows: [ChatRow] = []
            if let prompt = turn.prompt, !prompt.isEmpty {
                rows.append(ChatRow(id: "\(turn.id)-prompt", kind: .user(prompt, pending: turn.promptPending)))
            }
            if !turn.answer.isEmpty {
                rows.append(ChatRow(id: "\(turn.id)-answer", kind: .answer(turn.answer, streaming: turn.answerStreaming)))
            }
            return rows
        }
    }

    var activeTurnId: String? { turns.last(where: { $0.running })?.id }

    func turn(id: String) -> ChatTurnState? { turns.first { $0.id == id } }
}

struct ChatTurnState: Equatable {
    let id: String
    let prompt: String?
    /// True until `message_accepted` proves the gateway has the turn (C6.1).
    let promptPending: Bool
    let answer: String
    let answerStreaming: Bool
    let running: Bool
}

struct ChatRow: Identifiable, Equatable {
    enum Kind: Equatable {
        case user(String, pending: Bool)
        case answer(String, streaming: Bool)
    }

    let id: String
    let kind: Kind

    var text: String {
        switch kind {
        case let .user(text, _): text
        case let .answer(text, _): text
        }
    }

    var isStreaming: Bool {
        if case let .answer(_, streaming) = kind { return streaming }
        return false
    }
}

/// The chat a screen holds before a gateway is open: it answers nothing, so the view has a
/// model on every launch rather than an optional one.
@MainActor
final class NoopChat: ChatDriving {
    func open() async {}
    func send(_ text: String) async -> String? { nil }
    func stop() async {}
}

/// What the chat screen needs from a chat: one command each way (plan.md §7). The adapter's
/// `CoreChat` implements it over a `ChatHandle`; tests and the debug menu answer directly.
@MainActor
protocol ChatDriving: AnyObject {
    /// Open the chat: the gateway replays its history into `thread`.
    func open() async
    /// Send a prompt. Returns the turn id the core gave it, or nil when the send was refused.
    func send(_ text: String) async -> String?
    /// Stop is the `/stop` message on the running turn (C5.5).
    func stop() async
}
