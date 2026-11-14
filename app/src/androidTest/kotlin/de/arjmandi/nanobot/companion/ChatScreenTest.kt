package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.chat.ChatScreen
import de.arjmandi.nanobot.companion.chat.ChatThreadView
import de.arjmandi.nanobot.companion.chat.ChatTurnView
import de.arjmandi.nanobot.companion.chat.ChatViewModel
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The chat screen's taps (plan.md A3's Compose half): a stored answer is on screen when the chat
 * opens, typing swaps the microphone-sized Send for the primary button, sending goes through the
 * view model once, and a streaming turn offers Stop instead. The rules are `ChatViewModelTest`'s;
 * this is what only a composition can prove.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class ScriptedChat : ChatDriver {
        private val state = MutableStateFlow(ChatThreadView())
        override val thread: StateFlow<ChatThreadView> = state.asStateFlow()
        var sent: MutableList<String> = mutableListOf()
        var stops: Int = 0

        fun push(thread: ChatThreadView) {
            state.value = thread
        }

        override suspend fun open(chatId: String?) = Unit

        override suspend fun send(text: String): String? {
            sent += text
            return "turn-${sent.size}"
        }

        override suspend fun stop() {
            stops += 1
        }
    }

    private fun screen(chat: ScriptedChat, thread: ChatThreadView = ChatThreadView()): ChatViewModel {
        chat.push(thread)
        val model = ChatViewModel(chat)
        compose.setContent {
            NanobotTheme { ChatScreen(model = model) }
        }
        return model
    }

    private fun turn(answer: String, running: Boolean = false) = ChatTurnView(
        id = "t1",
        prompt = "Reply with exactly the word: pong",
        promptPending = false,
        answer = answer,
        answerStreaming = running,
        running = running,
    )

    @Test
    fun aStoredAnswerIsOnScreenWhenTheChatOpens() {
        screen(ScriptedChat(), ChatThreadView(listOf(turn(answer = "pong"))))

        compose.onNodeWithText("pong").assertIsDisplayed()
        compose.onNodeWithText("Reply with exactly the word: pong").assertIsDisplayed()
    }

    @Test
    fun typingTurnsThePrimaryButtonIntoASendThatSendsOnce() {
        val chat = ScriptedChat()
        screen(chat)

        compose.onNodeWithTag("chat.composer").performTextInput("Reply with exactly the word: pong")
        compose.onNodeWithTag("chat.send").performClick()

        assert(chat.sent == listOf("Reply with exactly the word: pong")) { "the button sends the typed draft once: ${chat.sent}" }
    }

    @Test
    fun aStreamingTurnOffersStopAndSendsNothing() {
        val chat = ScriptedChat()
        screen(chat, ChatThreadView(listOf(turn(answer = "counting", running = true))))

        compose.onNodeWithTag("chat.stop").assertIsDisplayed()
        compose.onNodeWithTag("chat.stop").performClick()

        assert(chat.stops == 1) { "the primary button stops the running turn" }
        assert(chat.sent.isEmpty()) { "and sends nothing" }
    }

    @Test
    fun anEmptyChatSaysWhatToDo() {
        screen(ScriptedChat())

        compose.onNodeWithTag("chat.empty").assertIsDisplayed()
    }
}
