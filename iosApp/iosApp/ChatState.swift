import Foundation

/// The thread as a Swift view model can reason about it: plain values, no framework types.
/// The adapter layer maps `core.ThreadState` onto this (plan.md D9: one reducer fills it,
/// whether the frames came from the live socket or from a replayed history).
/// Whether the stored page has been read (D46). The socket's `attach` carries no history, so a
/// thread with no rows is either a conversation nobody has used or one whose page has not
/// arrived, and the screen has to draw those two differently.
enum ThreadHistory: Equatable { case loading, loaded, failed }

/// What an empty thread draws: the read in flight, the read that failed, or a genuinely empty chat.
enum ThreadPlaceholder: Equatable { case none, skeleton, failed, empty }

struct ChatThreadState: Equatable {
    var turns: [ChatTurnState] = []
    /// The notices the core reduced (C7.3), owned by `StatesViewModel` on screen.
    var notices: [ThreadNotice] = []
    /// The subtasks this chat's agent owns (I8's dock).
    var subagents: [SubagentRow] = []
    /// The page read's state. A driver with no socket behind it starts `loaded`: it has nothing
    /// to load, so its empty thread really is empty.
    var history: ThreadHistory = .loaded

    /// Rows in reading order: the prompt, what nanobot did to answer it, then the answer.
    var rows: [ChatRow] {
        turns.flatMap { turn in
            var rows: [ChatRow] = []
            if let prompt = turn.prompt, !prompt.isEmpty {
                rows.append(ChatRow(id: "\(turn.id)-prompt", kind: .user(prompt, pending: turn.promptPending)))
            }
            if !turn.activity.isEmpty {
                rows.append(ChatRow(id: "\(turn.id)-activity", kind: .activity(turn.id)))
            }
            if !turn.answer.isEmpty {
                rows.append(ChatRow(id: "\(turn.id)-answer", kind: .answer(turn.readingBlocks, streaming: turn.answerStreaming)))
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
    /// What nanobot did on the way to the answer (I4), drawn as one cluster above it.
    var activity: [ChatActivityItem] = []
    /// The core's local compose time, which is the only start time a turn has.
    var startedAtMs: Int64? = nil
    /// The answer as the core parsed it (I5). Empty means the adapter has not parsed yet, and
    /// the row falls back to the plain text so a test can build a turn without a document.
    var blocks: [ReadingBlock] = []

    /// What the row draws: the parsed document, or the text as one paragraph when there is none.
    var readingBlocks: [ReadingBlock] {
        if !blocks.isEmpty { return blocks }
        return answer.isEmpty ? [] : [.paragraph(inlines: [.text(answer)])]
    }
}

struct ChatRow: Identifiable, Equatable {
    enum Kind: Equatable {
        case user(String, pending: Bool)
        /// A turn's activity cluster; the row carries the turn id, not a copy of its items.
        case activity(String)
        case answer([ReadingBlock], streaming: Bool)
    }

    let id: String
    let kind: Kind

    var text: String {
        switch kind {
        case let .user(text, _): text
        case .activity: ""
        case let .answer(blocks, _): blocks.map(\.plainText).joined(separator: "\n")
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
