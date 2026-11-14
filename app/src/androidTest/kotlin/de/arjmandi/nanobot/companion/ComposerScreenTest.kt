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
import de.arjmandi.nanobot.companion.chat.ChatViewModel
import de.arjmandi.nanobot.companion.composer.ChatModelPreset
import de.arjmandi.nanobot.companion.composer.ComposerDriving
import de.arjmandi.nanobot.companion.composer.ComposerSnapshot
import de.arjmandi.nanobot.companion.composer.ComposerViewModel
import de.arjmandi.nanobot.companion.composer.SessionAction
import de.arjmandi.nanobot.companion.composer.SessionActionsOverflow
import de.arjmandi.nanobot.companion.composer.PaletteSheet
import de.arjmandi.nanobot.companion.composer.WorkspaceAccess
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A5's taps (the Compose half): the queued chip's two buttons, the model pill's sheet with the
 * gateway's presets, the access chip's choice, and the bar's one overflow holding the session
 * actions. The button and queue rules are `ComposerViewModelTest`'s; this is what only a
 * composition can prove.
 */
@RunWith(AndroidJUnit4::class)
class ComposerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class ScriptedChat : ChatDriver {
        private val state = MutableStateFlow(ChatThreadView())
        override val thread: StateFlow<ChatThreadView> = state.asStateFlow()
        val sent = mutableListOf<String>()

        override suspend fun open(chatId: String?) = Unit

        override suspend fun send(text: String): String? {
            sent += text
            return "turn-${sent.size}"
        }

        override suspend fun stop() = Unit
    }

    private class ScriptedComposer : ComposerDriving {
        val presets = mutableListOf<String>()
        val accesses = mutableListOf<WorkspaceAccess>()
        var sendNowCount = 0
        var discarded = 0

        override suspend fun sendPrompt(text: String): Boolean = true

        override fun enqueue(text: String) = Unit

        override suspend fun stop() = Unit

        override suspend fun sendQueuedNow() {
            sendNowCount += 1
        }

        override fun discardQueued() {
            discarded += 1
        }

        override suspend fun setModelPreset(preset: String) {
            presets += preset
        }

        override suspend fun setWorkspaceScope(access: WorkspaceAccess) {
            accesses += access
        }

        override suspend fun loadPalette(text: String, cursor: Int): PaletteSheet? = null
    }

    private val presets = listOf(
        ChatModelPreset("default", "deepseek-v4.1-flash:cloud", "ollama", 32_768, active = false, isDefault = true),
        ChatModelPreset("primary", "gpt-5", "openai", 200_000, active = true, isDefault = false),
    )

    private fun screen(
        composer: ScriptedComposer = ScriptedComposer(),
        snapshot: ComposerSnapshot = ComposerSnapshot(modelName = "deepseek-v4.1-flash:cloud", contextWindowTokens = 200_000, usedTokens = 84_000),
    ): ChatViewModel {
        val model = ChatViewModel(ScriptedChat(), composer, MutableStateFlow(snapshot))
        compose.setContent {
            NanobotTheme {
                ChatScreen(
                    model = model,
                    sessionTitle = "Exact Word Pong Response",
                    sessionChatId = "uuid-a",
                    loadPresets = { presets },
                )
            }
        }
        return model
    }

    @Test
    fun typingTurnsTheButtonIntoASendThatSendsOnce() {
        screen()

        compose.onNodeWithTag("chat.composer").performTextInput("Reply with exactly the word: pong")
        compose.onNodeWithTag("chat.send").performClick()

        compose.onNodeWithTag("chat.send").assertDoesNotExist()
    }

    @Test
    fun theQueuedChipCanBeSentNow() {
        val composer = ScriptedComposer()
        screen(composer, ComposerSnapshot(queued = "waiting to send"))

        compose.onNodeWithTag("composer.queue").assertIsDisplayed()
        compose.onNodeWithTag("composer.queue.sendNow").performClick()

        assertEquals(1, composer.sendNowCount, "Send now steers the running turn instead of waiting")
    }

    @Test
    fun theQueuedChipCanBeDeleted() {
        val composer = ScriptedComposer()
        screen(composer, ComposerSnapshot(queued = "waiting to send"))

        compose.onNodeWithTag("composer.queue.delete").performClick()

        assertEquals(1, composer.discarded, "Delete drops the queued prompt")
    }

    @Test
    fun theModelPillOpensTheGatewaysPresets() {
        val composer = ScriptedComposer()
        screen(composer)

        compose.onNodeWithTag("composer.model").performClick()
        compose.onNodeWithText("primary").assertIsDisplayed()

        compose.onNodeWithTag("composer.model.primary").performClick()
        assertEquals(listOf("primary"), composer.presets, "the switch is the /model command (C6.2)")
    }

    @Test
    fun theAccessChipSwitchesToFull() {
        val composer = ScriptedComposer()
        screen(composer)

        compose.onNodeWithTag("composer.access").performClick()
        compose.onNodeWithTag("composer.access.full").performClick()

        assertEquals(listOf(WorkspaceAccess.Full), composer.accesses)
    }

    @Test
    fun theRingShowsTheUsedShareOfTheWindow() {
        screen(snapshot = ComposerSnapshot(contextWindowTokens = 200_000, usedTokens = 84_000))

        compose.onNodeWithText("42%").assertIsDisplayed()
    }

    @Test
    fun theBarOpensOneOverflowWithTheSessionActions() {
        val model = ComposerViewModel(ScriptedComposer())
        compose.setContent {
            NanobotTheme {
                androidx.compose.foundation.layout.Box {
                    SessionActionsOverflow(
                        composer = model,
                        expanded = true,
                        onDismiss = { model.closeOverflow() },
                    )
                }
            }
        }

        compose.onNodeWithTag("chat.sessionMenu.MessageList").assertIsDisplayed()
        compose.onNodeWithTag("chat.sessionMenu.SessionDetails").assertIsDisplayed()
        compose.onNodeWithTag("chat.sessionMenu.NewChat").assertIsDisplayed()

        compose.onNodeWithTag("chat.sessionMenu.MessageList").performClick()
        assertEquals(
            SessionAction.MessageList,
            model.chosenAction,
            "the overflow reports the choice to the shell, which performs it",
        )
    }
}
