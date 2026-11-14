package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.chat.ChatScreen
import de.arjmandi.nanobot.companion.chat.ChatThreadView
import de.arjmandi.nanobot.companion.chat.ChatViewModel
import de.arjmandi.nanobot.companion.composer.ComposerViewModel
import de.arjmandi.nanobot.companion.composer.EmptyComposerDriver
import de.arjmandi.nanobot.companion.debug.DebugStatesMenu
import de.arjmandi.nanobot.companion.facade.ConnectionFailure
import de.arjmandi.nanobot.companion.states.ConnectionDot
import de.arjmandi.nanobot.companion.states.ConnectionStatus
import de.arjmandi.nanobot.companion.states.ConnectionView
import de.arjmandi.nanobot.companion.states.DebugState
import de.arjmandi.nanobot.companion.states.SnackbarKind
import de.arjmandi.nanobot.companion.states.SubagentRow
import de.arjmandi.nanobot.companion.states.ThreadNotice
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A8's surfaces (the Compose half): the banner under the bar with the taxonomy's own sentence, the
 * snackbar above the composer with one transient line at a time, a recovery row with its two
 * actions, the goal as a strip and the dock with only active subtasks, and the DEBUB menu that
 * reaches all eight failures by hand. The precedence table is `StatesViewModelTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class StatesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The thread the screen collects: its notices and subtasks are what the states model folds, so a
     * test injects them the way the socket does rather than reaching past the model.
     */
    private class SilentChat(thread: ChatThreadView) : ChatDriver {
        private val state = MutableStateFlow(thread)
        override val thread: StateFlow<ChatThreadView> = state.asStateFlow()
        override suspend fun open(chatId: String?) = Unit
        override suspend fun send(text: String): String? = "turn-1"
        override suspend fun stop() = Unit
    }

    private fun chat(thread: ChatThreadView = ChatThreadView()): ChatViewModel = ChatViewModel(
        SilentChat(thread),
        EmptyComposerDriver(),
        MutableStateFlow(de.arjmandi.nanobot.companion.composer.ComposerSnapshot()),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun aFailedHandshakeShowsItsOwnSentenceUnderTheBar() {
        val model = chat()
        model.states.update(
            ConnectionView(ConnectionStatus.Failed, failure = ConnectionFailure.AuthFailed),
        )

        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        compose.onNodeWithTag("states.banner").assertIsDisplayed()
        compose.onNodeWithText("That key was refused. Check the gateway's key and try again.").assertIsDisplayed()
        compose.onNodeWithTag("states.retry").assertIsDisplayed()
    }

    @Test
    fun theSnackbarShowsTheMessageThatDidNotGoOutFirst() {
        val model = chat(
            ChatThreadView(
                notices = listOf(
                    ThreadNotice.Retry(id = "x1", attempt = 2, maxAttempts = 3, errorKind = "timeout", retryAtMs = System.currentTimeMillis() + 3_000),
                ),
            ),
        )

        compose.setContent { NanobotTheme { ChatScreen(model = model) } }
        compose.onNodeWithTag("states.snackbar").assertIsDisplayed()
        compose.onNodeWithText("The model timed out · retrying in 3s").assertIsDisplayed()

        // A message the gateway refused outranks the retry, and its action is a real resend.
        model.states.deliveryFailed("One more: does the queue survive a reload?")
        compose.waitForIdle()
        compose.onNodeWithText("The gateway did not take that message.").assertIsDisplayed()
        compose.onNodeWithTag("states.snackbar.action").assertIsDisplayed()
    }

    @Test
    fun aRecoveryRowAsksForTheDecisionAndCanBeDismissed() {
        val model = chat(
            ChatThreadView(
                notices = listOf(
                    ThreadNotice.Recovery(id = "r1", status = "awaiting_user", canContinue = true, reason = "A task was interrupted while writing."),
                ),
            ),
        )

        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        compose.onNodeWithTag("states.action.Continue").assertIsDisplayed()
        compose.onNodeWithTag("states.action.Dismiss").performClick()
        compose.waitForIdle()

        assertTrue(model.states.rows.isEmpty(), "the row goes at once, before the round trip comes back")
    }

    @Test
    fun theGoalIsAStripAndTheDockHoldsTheRunningSubtasks() {
        val model = chat(
            ChatThreadView(
                notices = listOf(ThreadNotice.Goal(id = "g1", active = true, status = "active", summary = "Ship the Android app")),
                subagents = listOf(
                    SubagentRow("a", "audit attachment limits", "running", 2, 4.5),
                    SubagentRow("b", "settled", "completed", 9, 60.0),
                ),
            ),
        )

        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        compose.onNodeWithTag("states.goal").assertIsDisplayed()
        compose.onNodeWithText("Ship the Android app").assertIsDisplayed()
        compose.onNodeWithTag("states.dock").assertIsDisplayed()
        compose.onNodeWithText("Running · step 2").assertIsDisplayed()
        compose.onNodeWithTag("states.row.Recovery").assertDoesNotExist()
    }

    @Test
    fun everyOneOfTheEightFailuresIsReachableFromTheDebugMenu() {
        val model = chat()
        compose.setContent {
            NanobotTheme {
                DebugStatesMenu(
                    states = model.states,
                    composer = ComposerViewModel(EmptyComposerDriver()),
                    onClose = {},
                )
            }
        }

        DebugState.failureScenarios.forEach { scenario ->
            compose.onNodeWithTag("debug.fail.${scenario.kind.name}").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(ConnectionDot.Failed, model.states.dot, "${scenario.title} shows the failed dot")
            assertTrue(model.states.banner != null, "${scenario.title} shows its sentence")
        }

        compose.onNodeWithTag("debug.chat.NotSent").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(SnackbarKind.Delivery, model.states.snackbar?.kind)

        compose.onNodeWithTag("debug.clear").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ConnectionDot.Open, model.states.dot, "the menu can put the app back")
    }
}
