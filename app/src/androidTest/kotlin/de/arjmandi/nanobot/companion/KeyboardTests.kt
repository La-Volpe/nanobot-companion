package de.arjmandi.nanobot.companion

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.arjmandi.nanobot.companion.activity.ActivityItemView
import de.arjmandi.nanobot.companion.activity.ActivityKind
import de.arjmandi.nanobot.companion.activity.ToolRunStatus
import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.chat.ChatScreen
import de.arjmandi.nanobot.companion.chat.ChatThreadView
import de.arjmandi.nanobot.companion.chat.ChatTurnView
import de.arjmandi.nanobot.companion.chat.ChatViewModel
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Putting the keyboard away (the Android half of the owner report iOS fixed in `2a5c060`): a flick
 * on the transcript and a tap outside the field both had to leave it up, and only Back closed it.
 * The Android answers are `Modifier.imeNestedScroll()` on the thread, which is the platform's
 * interactive dismissal and the twin of `.scrollDismissesKeyboard(.interactively)`, plus one
 * `clearFocus` on the conversation, which is only correct if a tool row's disclosure and a
 * reasoning fold still fire.
 *
 * Whether the keyboard went is asked of the device (`dumpsys input_method`'s `mInputShown`), not of
 * a flag: a Compose focus bit says the field let go, and that is not the same claim as the IME
 * having left the screen.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardTests {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class ScriptedChat : ChatDriver {
        private val state = MutableStateFlow(ChatThreadView())
        override val thread: StateFlow<ChatThreadView> = state.asStateFlow()
        val sent = mutableListOf<String>()

        fun push(thread: ChatThreadView) {
            state.value = thread
        }

        override suspend fun open(chatId: String?) = Unit

        override suspend fun send(text: String): String? {
            sent += text
            return "turn-${sent.size}"
        }

        override suspend fun stop() = Unit
    }

    private val driver = ScriptedChat()

    /**
     * Two turns and one cluster: the last turn is the one on screen when the chat opens, and only
     * it has activity, so "the" tool row and "the" reasoning fold are unambiguous.
     */
    private fun history() = ChatThreadView(
        listOf(
            ChatTurnView(
                id = "t1",
                prompt = "Reply with exactly the word: ping",
                promptPending = false,
                answer = "ping",
                answerStreaming = false,
                running = false,
            ),
            ChatTurnView(
                id = "t2",
                prompt = "Reply with exactly the word: pong",
                promptPending = false,
                answer = "pong",
                answerStreaming = false,
                running = false,
                activity = listOf(
                    ActivityItemView("r1", ActivityKind.Reasoning(text = "checked the write path", done = true)),
                    ActivityItemView("tool:1", ActivityKind.Tool("run_command", "pytest -q", ToolRunStatus.Done, "12 passed")),
                ),
            ),
        ),
    )

    private fun chat(): ChatViewModel {
        val model = ChatViewModel(driver)
        compose.setContent { NanobotTheme { ChatScreen(model = model) } }
        driver.push(history())
        compose.waitForIdle()
        return model
    }

    /**
     * The device's own answer to "is the keyboard up", not the composition's. Read whole and
     * matched here: `executeShellCommand` runs the command without a shell, so a pipe is an
     * argument to `dumpsys` rather than a filter.
     */
    private fun imeLine(): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd: ParcelFileDescriptor = automation.executeShellCommand("dumpsys input_method")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes().decodeToString()
        return Regex("mInputShown=(true|false)").find(dump)?.value ?: "mInputShown=<absent>"
    }

    private fun imeShown(): Boolean = imeLine() == "mInputShown=true"

    private fun awaitIme(shown: Boolean, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (imeShown() == shown) return imeLine()
            Thread.sleep(100)
        }
        return imeLine()
    }

    /**
     * Typing is what makes the keyboard real: a focused field and a keyboard look alike otherwise.
     *
     * A machine that draws no software keyboard cannot answer the question these cases ask, and a hosted
     * emulator is such a machine — it reports a hardware keyboard and keeps the IME away however
     * `show_ime_with_hard_keyboard` is set (AGENTS gotcha 28, the same trap the iOS `KeyboardTests` skip for).
     * Skipped with the measured value rather than failed, so a real regression still fails and an absent
     * keyboard says what it is.
     */
    private fun focusComposer() {
        compose.onNodeWithTag("chat.composer").performClick()
        compose.waitForIdle()
        val ime = awaitIme(shown = true)
        assumeTrue(
            "no software keyboard was raised on this device ($ime); the keyboard cases cannot answer here",
            ime == "mInputShown=true",
        )
    }

    @Test
    fun aFlickOnTheTranscriptPutsTheKeyboardAway() {
        chat()
        focusComposer()

        // The flick an iPhone user tries first: the transcript dragged down, which is the direction
        // `imeNestedScroll` hands to the IME before the list gets it.
        compose.onNodeWithTag("chat.thread").performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals("mInputShown=false", awaitIme(shown = false), "a downward flick on the transcript leaves no keyboard")
    }

    @Test
    fun aTapOnTheTranscriptPutsTheKeyboardAway() {
        chat()
        focusComposer()

        // A prompt bubble is text, not a control: a tap there is the reader's, not a row's.
        compose.onAllNodesWithTag("chat.prompt")[0].performClick()
        compose.waitForIdle()

        assertEquals("mInputShown=false", awaitIme(shown = false), "a tap on the transcript leaves no keyboard")
        compose.onNodeWithTag("chat.composer").assertIsNotFocused()
    }

    @Test
    fun aTapOnAToolRowStillOpensItsDisclosure() {
        chat()
        focusComposer()

        // The cluster opens on the head, and its rows and their folds are behind it: three controls
        // a dismissal gesture at this level could swallow.
        compose.onNodeWithTag("activity.head").performClick()
        compose.onNodeWithTag("activity.reasoning").assertIsDisplayed()

        compose.onNodeWithTag("activity.reasoning").performClick()
        compose.onNodeWithTag("activity.reasoning.details").assertIsDisplayed()

        compose.onNodeWithTag("activity.tool").performClick()
        compose.onNodeWithText("Arguments").assertIsDisplayed()
    }

    @Test
    fun theSendButtonLeavesTheFieldFocused() {
        chat()
        focusComposer()

        compose.onNodeWithTag("chat.composer").performTextInput("sent with the keyboard up")
        compose.onNodeWithTag("chat.send").performClick()
        compose.waitForIdle()

        assertEquals(listOf("sent with the keyboard up"), driver.sent, "the tap was the button's, and it sent")
        compose.onNodeWithTag("chat.composer").assertIsFocused()
        assertEquals("mInputShown=true", imeLine(), "sending does not take the keyboard away")
    }

    @Test
    fun theComposerStillSitsAboveTheKeyboard() {
        chat()
        val atRest = compose.onNodeWithTag("chat.composer").getUnclippedBoundsInRoot().bottom.value
        focusComposer()

        // The inset is the device's, in the same pixels the root's bounds are in.
        val root = compose.activity.window.decorView
        val density = compose.activity.resources.displayMetrics.density
        val ime = ViewCompat.getRootWindowInsets(root)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val keyboardTop = (root.height - ime) / density
        val raised = compose.onNodeWithTag("chat.composer").getUnclippedBoundsInRoot().bottom.value

        assertTrue(ime > 0, "the device reported a keyboard inset ($ime px)")
        assertTrue(raised <= keyboardTop + 1f, "the composer sits above the keyboard ($raised dp vs $keyboardTop dp)")
        assertTrue(raised < atRest, "and it was pushed up from where it rests ($raised dp vs $atRest dp)")
    }
}
