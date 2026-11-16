package de.arjmandi.nanobot.companion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.chat.ChatScreen
import de.arjmandi.nanobot.companion.chat.ChatThreadView
import de.arjmandi.nanobot.companion.chat.ChatViewModel
import de.arjmandi.nanobot.companion.composer.ComposerDriving
import de.arjmandi.nanobot.companion.composer.ComposerSnapshot
import de.arjmandi.nanobot.companion.composer.PaletteSheet
import de.arjmandi.nanobot.companion.composer.WorkspaceAccess
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import de.arjmandi.nanobot.companion.voice.AudioRecording
import de.arjmandi.nanobot.companion.voice.InMemoryVoiceHints
import de.arjmandi.nanobot.companion.voice.VoiceDriving
import de.arjmandi.nanobot.companion.voice.VoiceHints
import de.arjmandi.nanobot.companion.voice.VoiceMeter
import de.arjmandi.nanobot.companion.voice.VoiceNotice
import de.arjmandi.nanobot.companion.voice.VoiceOutcome
import de.arjmandi.nanobot.companion.voice.VoiceViewModel
import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * T48's voice drawing, the Compose half: the timer on screen while a press records, the transcribing
 * state that leaves the field usable while the button goes inactive, the drag-up lock with its Send
 * and its cancel target, and the one-time "Hold to talk" hint. The gesture machine itself is
 * `VoiceViewModelTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class VoiceScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The composer asks for the microphone before it records, which is the app's rule; a device
     * test grants it the way the user would. Revoking it is not done here: revoking a runtime
     * permission stops the app's process, which would take the instrumentation run with it.
     */
    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    private class FakeRecorder : AudioRecording {
        override fun start() = Unit
        override fun stop(): ByteArray = byteArrayOf(1, 2, 3, 4)
        override fun cancel() = Unit
    }

    /** Answers when the test opens the gate, so the transcribing state can be looked at. */
    private class FakeVoice(
        private val gate: CompletableDeferred<String>? = null,
        private val text: String = "check the write path first",
    ) : VoiceDriving {
        override suspend fun transcribe(pcm: ByteArray, durationMs: Long): VoiceOutcome =
            VoiceOutcome.Transcribed(gate?.await() ?: text)
    }

    private class SilentChat : ChatDriver {
        private val state = MutableStateFlow(ChatThreadView())
        override val thread: StateFlow<ChatThreadView> = state.asStateFlow()
        override suspend fun open(chatId: String?) = Unit
        override suspend fun send(text: String): String? = "turn-1"
        override suspend fun stop() = Unit
    }

    private object NoComposer : ComposerDriving {
        override suspend fun sendPrompt(text: String): Boolean = true
        override fun enqueue(text: String) = Unit
        override suspend fun stop() = Unit
        override suspend fun sendQueuedNow() = Unit
        override fun discardQueued() = Unit
        override suspend fun setModelPreset(preset: String): Boolean = true
        override suspend fun setWorkspaceScope(access: WorkspaceAccess) = Unit
        override suspend fun loadPalette(text: String, cursor: Int): PaletteSheet? = null
    }

    private fun chat(
        driver: VoiceDriving = FakeVoice(),
        hints: VoiceHints = InMemoryVoiceHints(),
    ) = ChatViewModel(
        SilentChat(),
        NoComposer,
        MutableStateFlow(ComposerSnapshot()),
        voiceDriver = driver,
        recorder = { FakeRecorder() },
        voiceHints = hints,
    )

    /** The timer the owner could not see: the meter draws the model's clock (T48). */
    @Test
    fun theMeterDrawsTheClockAndTheWords() {
        val model = VoiceViewModel(FakeRecorder(), FakeVoice())
        model.beginHold(nowMs = 0)
        model.tick(nowMs = 7_000)

        compose.setContent { NanobotTheme { VoiceMeter(model = model, onCancel = {}) } }

        compose.onNodeWithTag("voice.meter").assertIsDisplayed()
        compose.onNodeWithTag("voice.timer").assertTextEquals("0:07")
        compose.onNodeWithText("Release to send").assertIsDisplayed()
    }

    /** A drag up hands the recording over: the meter says Send and offers the way out. */
    @Test
    fun aLockedRecordingOffersSendAndCancel() {
        val model = VoiceViewModel(FakeRecorder(), FakeVoice())
        model.beginHold(nowMs = 0)
        model.lock()

        compose.setContent { NanobotTheme { VoiceMeter(model = model, onCancel = { model.cancelRecording() }) } }

        compose.onNodeWithText("Tap Send to finish").assertIsDisplayed()
        compose.onNodeWithTag("voice.cancel").performClick()
        compose.waitForIdle()

        assertEquals(VoiceViewModel.Phase.Idle, model.phase, "the cancel target throws the recording away")
        compose.onNodeWithTag("voice.meter").assertDoesNotExist()
    }

    /**
     * While the gateway transcribes, the button is the feedback and the composer keeps working:
     * the field is still editable and still sends, which is the owner's correction to the brief.
     */
    @Test
    fun transcribingLeavesTheComposerUsable() {
        val gate = CompletableDeferred<String>()
        val model = chat(driver = FakeVoice(gate))
        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        compose.onNodeWithTag("chat.voice").performTouchInput { down(center) }
        Thread.sleep(900)
        compose.onNodeWithTag("chat.stop").performTouchInput { up() }
        compose.waitForIdle()

        assertTrue(model.voice.transcribing, "900 ms is speech, so the gateway is asked")
        compose.onNodeWithTag("voice.transcribing").assertIsDisplayed()
        compose.onNodeWithTag("chat.voice").assertIsNotEnabled()
        compose.onNodeWithTag("chat.voice.arc").assertIsDisplayed()

        // The composer is not locked: typing still works while the transcript is on its way.
        compose.onNodeWithTag("chat.composer").performTextInput("typed while waiting")
        compose.waitForIdle()
        assertEquals("typed while waiting", model.composer.draft)

        gate.complete("check the write path first")
        compose.waitForIdle()
        assertEquals(VoiceViewModel.Phase.Idle, model.voice.phase)
        compose.onNodeWithTag("chat.send").assertIsEnabled()
        assertTrue(model.draft.endsWith("check the write path first"), "the transcript lands after the typing")
    }

    /** A quick tap is a discard with one explanation, and it says nothing on the second tap (T48). */
    @Test
    fun aTapShowsHoldToTalkOnce() {
        val hints = InMemoryVoiceHints()
        val model = chat(hints = hints)
        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        // One injection block, so the down and the up are the same press and not two presses
        // 800 ms apart — a real finger on this emulator can outlast the floor between two calls.
        compose.onNodeWithTag("chat.voice").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()

        compose.waitUntil(3_000) { model.voice.notice != null }
        assertEquals(VoiceNotice.HoldToTalk, model.voice.notice, "a tap is discarded and explained")
        compose.onNodeWithText("Hold to talk").assertIsDisplayed()
        compose.onNodeWithTag("voice.notice.dismiss").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat.voice").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithText("Hold to talk").assertDoesNotExist()
        assertEquals(VoiceViewModel.Phase.Idle, model.voice.phase, "a tap leaves nothing running")
    }

    /**
     * The send tap is a send (T31). The button becomes the microphone the moment the field empties,
     * and the press that sent the message must not become a recording.
     */
    @Test
    fun aSendPressDoesNotStartARecording() {
        val model = chat()
        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        compose.onNodeWithTag("chat.composer").performTextInput("a typed question")
        compose.waitForIdle()
        compose.onNodeWithTag("chat.send").performTouchInput { down(center) }
        compose.onNodeWithTag("chat.send").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(VoiceViewModel.Phase.Idle, model.voice.phase, "a send is not a recording")
    }

    /** Drag up with a finger, then tap the button that is now Send: the transcript lands. */
    @Test
    fun aDragUpLockThenSendLandsTheTranscript() {
        val model = chat()
        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        compose.onNodeWithTag("chat.voice").performTouchInput {
            down(center)
            moveBy(Offset(0f, -240f))
            up()
        }
        compose.waitForIdle()

        assertTrue(model.voice.locked, "the drag locked the recording")
        assertEquals(VoiceViewModel.Phase.Recording, model.voice.phase, "and the lift did not stop it")

        compose.onNodeWithTag("chat.send").performClick()
        compose.waitForIdle()

        assertEquals("check the write path first", model.composer.draft, "the transcript lands in the field")
        assertEquals(VoiceViewModel.Phase.Idle, model.voice.phase)
    }
}
