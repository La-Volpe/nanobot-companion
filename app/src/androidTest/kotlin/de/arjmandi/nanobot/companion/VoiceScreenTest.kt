package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
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
import de.arjmandi.nanobot.companion.voice.VoiceDriving
import de.arjmandi.nanobot.companion.voice.VoiceMeter
import de.arjmandi.nanobot.companion.voice.VoiceOutcome
import de.arjmandi.nanobot.companion.voice.VoiceViewModel
import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A6's voice drawing (the Compose half): the meter's clock while recording, "Transcribing…" while
 * the gateway works, and the one button following the two gestures from Voice to Stop. The gesture
 * machine itself is `VoiceViewModelTest`'s.
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

    private class FakeVoice(private val text: String = "check the write path first") : VoiceDriving {
        override suspend fun transcribe(pcm: ByteArray, durationMs: Long): VoiceOutcome = VoiceOutcome.Transcribed(text)
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
        override suspend fun setModelPreset(preset: String) = Unit
        override suspend fun setWorkspaceScope(access: WorkspaceAccess) = Unit
        override suspend fun loadPalette(text: String, cursor: Int): PaletteSheet? = null
    }

    @Test
    fun theMeterCountsUpInMinutesAndSeconds() {
        val model = VoiceViewModel(FakeRecorder(), FakeVoice(), CoroutineScope(Dispatchers.Unconfined))
        model.beginHold(nowMs = 0)
        model.tick(nowMs = 7_000)

        compose.setContent { NanobotTheme { VoiceMeter(model = model) } }

        compose.onNodeWithTag("voice.meter").assertIsDisplayed()
        compose.onNodeWithText("0:07").assertIsDisplayed()
        compose.onNodeWithText("Release to send").assertIsDisplayed()
    }

    @Test
    fun theMeterSaysTapToStopInTapMode() {
        val model = VoiceViewModel(FakeRecorder(), FakeVoice(), CoroutineScope(Dispatchers.Unconfined))
        model.beginHold(nowMs = 0)
        model.armTapMode()

        compose.setContent { NanobotTheme { VoiceMeter(model = model) } }

        compose.onNodeWithText("Tap to stop").assertIsDisplayed()
    }

    /**
     * A notice is a line on the composer, so it needs a control that clears it (T31). The owner's
     * gateway has no speech provider: every recording is refused, and the refusal is what a reader
     * has to be able to put away.
     */
    @Test
    fun theNoticeSaysWhyAndCanBePutAway() {
        val model = ChatViewModel(
            SilentChat(),
            NoComposer,
            MutableStateFlow(ComposerSnapshot()),
            voiceDriver = FakeVoice("check the write path first"),
            recorder = { FakeRecorder() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        // A press under the gateway's floor is refused, and the refusal is a sentence above the field.
        compose.onNodeWithTag("chat.voice").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("chat.stop").performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithText("Hold a little longer to record voice.").assertIsDisplayed()

        compose.onNodeWithTag("voice.notice.dismiss").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Hold a little longer to record voice.").assertDoesNotExist()
    }

    /**
     * The send tap is a send (T31). The button becomes the microphone the moment the field empties,
     * and the press that sent the message must not become a recording.
     */
    @Test
    fun aSendPressDoesNotStartARecording() {
        val model = ChatViewModel(
            SilentChat(),
            NoComposer,
            MutableStateFlow(ComposerSnapshot()),
            voiceDriver = FakeVoice("check the write path first"),
            recorder = { FakeRecorder() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        compose.onNodeWithTag("chat.composer").performTextInput("a typed question")
        compose.waitForIdle()
        compose.onNodeWithTag("chat.send").performTouchInput { down(center) }
        compose.onNodeWithTag("chat.send").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(VoiceViewModel.Phase.Idle, model.voice.phase, "a send is not a recording")
    }

    @Test
    fun theTwoTapsDictateAndTheTranscriptLands() {
        val model = ChatViewModel(
            SilentChat(),
            NoComposer,
            MutableStateFlow(ComposerSnapshot()),
            voiceDriver = FakeVoice("check the write path first"),
            recorder = { FakeRecorder() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent { NanobotTheme { ChatScreen(model = model) } }

        // An empty field offers the microphone; the first tap opens it and arms tap-to-dictate.
        compose.onNodeWithTag("chat.voice").performClick()
        compose.waitForIdle()
        assertEquals(VoiceViewModel.Phase.Recording, model.voice.phase, "the first tap starts dictating")

        // While a recording is in flight the same button is Stop.
        compose.onNodeWithTag("chat.stop").assertIsDisplayed()
        compose.onNodeWithTag("chat.stop").performClick()
        compose.waitForIdle()

        assertEquals(VoiceViewModel.Phase.Idle, model.voice.phase)
        assertEquals("check the write path first", model.composer.draft, "the transcript lands in the field")
    }
}
