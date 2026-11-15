import Foundation
import Testing
@testable import iosApp

/// The chat's two red lines (plan.md I3) and the rule that decides what the primary button does.
@MainActor
struct ChatViewModelTests {

    final class FakeChat: ChatDriving {
        var sent: [String] = []
        var stops = 0
        var opens = 0
        var refuses = false

        func open() async { opens += 1 }

        func send(_ text: String) async -> String? {
            sent.append(text)
            return refuses ? nil : "turn-\(sent.count)"
        }

        func stop() async { stops += 1 }
    }

    private func turn(
        id: String = "t1",
        prompt: String? = nil,
        pending: Bool = false,
        answer: String = "",
        streaming: Bool = false,
        running: Bool = false
    ) -> ChatTurnState {
        ChatTurnState(id: id, prompt: prompt, promptPending: pending, answer: answer, answerStreaming: streaming, running: running)
    }

    /// The plan's red line: the bubble is real before the gateway speaks, and stops being
    /// marked pending when `message_accepted` arrives.
    @Test func optimisticBubbleReconcilesOnAccept() {
        let model = ChatViewModel(chat: FakeChat())

        model.update(thread: ChatThreadState(turns: [turn(prompt: "hello", pending: true, running: true)]))
        #expect(model.rows.count == 1, "the prompt is on screen before the gateway answers")
        guard case let .user(text, pending) = model.rows[0].kind else {
            Issue.record("the first row is the prompt")
            return
        }
        #expect(text == "hello")
        #expect(pending, "and it is marked pending until the ack")

        model.update(thread: ChatThreadState(turns: [turn(prompt: "hello", pending: false, running: true)]))
        guard case let .user(_, accepted) = model.rows[0].kind else {
            Issue.record("the first row is still the prompt")
            return
        }
        #expect(accepted == false, "the ack makes the bubble real")
    }

    @Test func streamingAppearsIncrementally() {
        let model = ChatViewModel(chat: FakeChat())

        model.update(thread: ChatThreadState(turns: [turn(prompt: "q", answer: "He", streaming: true, running: true)]))
        #expect(model.rows.last?.text == "He")

        model.update(thread: ChatThreadState(turns: [turn(prompt: "q", answer: "Hello there", streaming: true, running: true)]))
        #expect(model.rows.last?.text == "Hello there", "each frame replaces the answer's text rather than appending a row")
        #expect(model.rows.count == 2, "one prompt row and one answer row")
        #expect(model.rows.last?.isStreaming == true)

        model.update(thread: ChatThreadState(turns: [turn(prompt: "q", answer: "Hello there", running: false)]))
        #expect(model.rows.last?.isStreaming == false, "a finished turn stops the caret")
    }

    @Test func stopVsSendRule() {
        let model = ChatViewModel(chat: FakeChat())

        model.update(thread: ChatThreadState(turns: [turn(running: true)]))
        #expect(model.showsStop, "a running turn with an empty field is a Stop button")

        model.draft = "one more thing"
        #expect(model.showsStop == false, "typing turns the button back into Send")
        #expect(model.canSend)

        model.draft = "   "
        #expect(model.canSend == false, "whitespace is not a message")
    }

    @Test func sendingClearsTheDraftAndRefusalGivesItBack() async {
        let chat = FakeChat()
        let model = ChatViewModel(chat: chat)

        model.draft = "hello"
        await model.send()

        #expect(chat.sent == ["hello"])
        #expect(model.draft.isEmpty, "an accepted send clears what was typed")

        chat.refuses = true
        model.draft = "keep me"
        await model.send()

        #expect(model.draft == "keep me", "a refused send gives the text back (C6.1)")
        #expect(model.lastRefusal != nil)
    }

    @Test func thePrimaryButtonStopsWhileStreamingAndSendsOtherwise() async {
        let chat = FakeChat()
        let model = ChatViewModel(chat: chat)
        model.update(thread: ChatThreadState(turns: [turn(running: true)]))

        await model.primaryAction()
        #expect(chat.stops == 1, "an empty field during a turn means Stop")

        model.update(thread: ChatThreadState(turns: [turn(running: false)]))
        model.draft = "next"
        await model.primaryAction()
        #expect(chat.sent == ["next"])
    }

    @Test func openingTheThreadAsksForItOnce() async {
        let chat = FakeChat()
        let model = ChatViewModel(chat: chat)

        await model.open()

        #expect(chat.opens == 1)
    }

    /// The auto-scroll rule T9 asked for (webui `ThreadViewport.tsx:1002`, `thread-motion.ts:353`):
    /// the thread follows the stream only while the reader is at the end. A reader who scrolled up
    /// keeps their place, and coming back to the end resumes following.
    @Test func theTailFollowsOnlyWhileTheReaderIsAtIt() {
        let model = ChatViewModel(chat: FakeChat())
        model.update(thread: ChatThreadState(turns: [turn(prompt: "q", answer: "1", streaming: true, running: true)]))

        #expect(model.viewport.following, "a fresh thread follows its own tail")

        model.scrolled(distanceFromBottom: 600)
        #expect(model.viewport.following == false, "a reader who scrolled up is not dragged back")
        #expect(model.viewport.followsNewContent == false, "so the next delta does not scroll the thread")

        model.scrolled(distanceFromBottom: 0)
        #expect(model.viewport.followsNewContent, "back at the end, the stream follows again")
    }

    @Test func sendingPinsTheReaderToTheTail() async {
        let model = ChatViewModel(chat: FakeChat())
        model.scrolled(distanceFromBottom: 600)

        model.draft = "go on"
        await model.primaryAction()

        #expect(model.viewport.followsNewContent, "a prompt is a request to see its answer arrive")
    }
}
