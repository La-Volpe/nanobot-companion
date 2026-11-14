import Foundation

/// The chat screen's state and its two rules (plan.md I3): the optimistic bubble is on screen
/// before the gateway answers and is marked pending until it does, and the primary button is
/// Stop only while a turn is running and there is nothing new to send — the WebUI's own rule
/// (`showStopButton = isStreaming && !canSend`).
@MainActor
final class ChatViewModel: ObservableObject {

    @Published private(set) var thread = ChatThreadState()
    @Published var draft: String = ""
    @Published private(set) var lastRefusal: String?

    private var chat: ChatDriving

    init(chat: ChatDriving, thread: ChatThreadState = ChatThreadState()) {
        self.chat = chat
        self.thread = thread
    }

    var rows: [ChatRow] { thread.rows }

    var isStreaming: Bool { thread.turns.contains { $0.running } }

    /// The WebUI rule, transcribed (design README, "Controls worth trying").
    var showsStop: Bool { isStreaming && !canSend }

    /// Typed text is what makes the button Send again: pressing it queues a steer, it does not
    /// stop the turn (C5.5).
    var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// The screen swaps the driver once a gateway is open and the chat has an id.
    func attach(_ chat: ChatDriving) {
        self.chat = chat
    }

    func update(thread: ChatThreadState) {
        self.thread = thread
    }

    func open() async {
        await chat.open()
    }

    /// The optimistic bubble is not invented here: the core's reducer adds it, and this only
    /// forwards what the send produced (D9 — one reducer, two feeds).
    func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isStreaming || canSend else { return }
        draft = ""
        lastRefusal = nil
        let turnId = await chat.send(text)
        if turnId == nil {
            // A refused send gives the text back rather than losing it (C6.1).
            draft = text
            lastRefusal = "The gateway did not take that message."
        }
    }

    func stop() async {
        await chat.stop()
    }

    /// What the primary button does: stop while streaming with an empty field, send otherwise.
    func primaryAction() async {
        if showsStop {
            await stop()
        } else {
            await send()
        }
    }
}
