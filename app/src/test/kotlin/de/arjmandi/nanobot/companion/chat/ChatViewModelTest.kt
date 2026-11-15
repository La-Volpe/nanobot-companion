package de.arjmandi.nanobot.companion.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest

/**
 * The chat screen's two rules (plan.md A3): the optimistic bubble is on screen before the gateway
 * answers and is marked pending until it does, and the primary button is Stop only while a turn
 * runs with nothing new to send — the WebUI's own rule, `isStreaming && !canSend`.
 *
 * The streaming case is the one the plan names: deltas arrive many times a second, so what the
 * screen holds is one growth of one bubble per core emission — never a row per character.
 */
class ChatViewModelTest {

    /** A driver whose thread the test pushes at, so streaming is what the test says it is. */
    private class ScriptedChat : ChatDriver {
        private val state = MutableStateFlow(ChatThreadView())
        override val thread: StateFlow<ChatThreadView> = state.asStateFlow()

        var sent: MutableList<String> = mutableListOf()
        var stops: Int = 0
        var opens: MutableList<String?> = mutableListOf()
        var refuseNextSend: Boolean = false

        var emissions: Int = 0

        fun push(thread: ChatThreadView) {
            emissions += 1
            state.value = thread
        }

        override suspend fun open(chatId: String?) {
            opens += chatId
        }

        override suspend fun send(text: String): String? {
            sent += text
            return if (refuseNextSend) null else "turn-${sent.size}"
        }

        override suspend fun stop() {
            stops += 1
        }
    }

    private fun turn(
        id: String = "t1",
        answer: String,
        running: Boolean = true,
        streaming: Boolean = true,
        prompt: String? = "Count slowly",
    ) = ChatTurnView(
        id = id,
        prompt = prompt,
        promptPending = false,
        answer = answer,
        answerStreaming = streaming,
        running = running,
    )

    @Test
    fun streamingStateEmitsBatched() = runTest {
        val chat = ScriptedChat()
        val model = ChatViewModel(chat)

        model.draft = "Count slowly"
        model.primaryAction()
        chat.push(ChatThreadView(listOf(turn(answer = "1"))))
        chat.push(ChatThreadView(listOf(turn(answer = "1\n2"))))
        chat.push(ChatThreadView(listOf(turn(answer = "1\n2\n3"))))

        // Three deltas, one row: the bubble grows, it is never a row per delta.
        assertEquals(1, model.turns.size, "an answer is one row that grows, not one row per delta")
        assertEquals("1\n2\n3", model.turns.single().answer)
        assertTrue(model.isStreaming, "the turn is still running")
        assertEquals(3, chat.emissions)
    }

    @Test
    fun stopVsSendRule() = runTest {
        val chat = ScriptedChat()
        val model = ChatViewModel(chat)
        chat.push(ChatThreadView(listOf(turn(answer = ""))))

        // Streaming with an empty field: the primary button is Stop.
        assertTrue(model.isStreaming)
        assertFalse(model.canSend)
        assertTrue(model.showsStop, "isStreaming && !canSend is the WebUI's rule for the Stop button")

        // Typing turns it back into Send: pressing it then queues a steer, it does not stop the turn.
        model.draft = "then count backwards"
        assertTrue(model.canSend)
        assertFalse(model.showsStop, "a typed prompt makes the button Send again")
    }

    @Test
    fun thePrimaryButtonSendsWhatIsTyped() = runTest {
        val chat = ScriptedChat()
        val model = ChatViewModel(chat)
        model.draft = "  Reply with exactly the word: pong  "

        model.primaryAction()

        assertEquals(listOf("Reply with exactly the word: pong"), chat.sent, "the draft is trimmed on the way out")
        assertEquals("", model.draft, "and the field is empty once the gateway took it")
        assertEquals(0, chat.stops)
    }

    @Test
    fun thePrimaryButtonStopsWhileStreamingWithNothingToSend() = runTest {
        val chat = ScriptedChat()
        val model = ChatViewModel(chat)
        chat.push(ChatThreadView(listOf(turn(answer = "counting"))))

        model.primaryAction()

        assertEquals(1, chat.stops, "an empty field while streaming means Stop")
        assertTrue(chat.sent.isEmpty())
    }

    @Test
    fun aRefusedSendGivesTheTextBack() = runTest {
        val chat = ScriptedChat()
        chat.refuseNextSend = true
        val model = ChatViewModel(chat)
        model.draft = "a message the socket would not take"

        model.primaryAction()

        assertEquals("a message the socket would not take", model.draft, "a refused send does not lose what was typed")
        assertTrue(model.lastRefusalRes != null, "and it says so, rather than silently doing nothing")
    }

    @Test
    fun openingAChatAsksForThatChat() = runTest {
        val chat = ScriptedChat()
        val model = ChatViewModel(chat)

        model.open("uuid-a")

        assertEquals(listOf<String?>("uuid-a"), chat.opens, "the drawer's row is the chat the core opens")
    }

    @Test
    fun historyIsWhatTheCoreReplays() = runTest {
        val chat = ScriptedChat()
        val model = ChatViewModel(chat)
        chat.push(
            ChatThreadView(
                listOf(
                    turn(id = "t1", answer = "earlier answer", running = false, streaming = false, prompt = "earlier question"),
                    turn(id = "t2", answer = "second answer", running = false, streaming = false, prompt = "second question"),
                ),
            ),
        )

        assertEquals(2, model.turns.size)
        assertEquals("earlier question", model.turns.first().prompt)
        assertFalse(model.isStreaming, "a replayed history is not a running turn")
        assertNull(model.lastRefusalRes)
    }

    /**
     * The auto-scroll rule T9 asked for (webui `ThreadViewport.tsx:1002`, `thread-motion.ts:353`):
     * the thread follows the stream only while the reader is at the end. A reader who scrolled up
     * keeps their place, and coming back to the end resumes following.
     */
    @Test
    fun theTailFollowsOnlyWhileTheReaderIsAtIt() = runTest {
        val chat = ScriptedChat()
        val model = ChatViewModel(chat)
        chat.push(ChatThreadView(listOf(turn(answer = "1"))))

        assertTrue(model.viewport.following, "a fresh thread follows its own tail")

        model.scrolled(distanceFromBottomPx = 600)
        assertFalse(model.viewport.following, "a reader who scrolled up is not dragged back")
        assertFalse(model.viewport.followsNewContent(), "so the next delta does not scroll the thread")

        model.scrolled(distanceFromBottomPx = 0)
        assertTrue(model.viewport.followsNewContent(), "back at the end, the stream follows again")
    }

    /** Sending is the reader asking for the latest, whatever they were reading before. */
    @Test
    fun sendingPinsTheReaderToTheTail() = runTest {
        val chat = ScriptedChat()
        val model = ChatViewModel(chat)
        model.scrolled(distanceFromBottomPx = 600)

        model.draft = "go on"
        model.primaryAction()

        assertTrue(model.viewport.followsNewContent(), "a prompt is a request to see its answer arrive")
    }
}
