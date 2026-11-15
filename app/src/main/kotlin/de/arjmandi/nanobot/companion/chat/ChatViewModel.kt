package de.arjmandi.nanobot.companion.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.arjmandi.nanobot.companion.activity.ActivityViewModel
import de.arjmandi.nanobot.companion.composer.ComposerDriving
import de.arjmandi.nanobot.companion.composer.ComposerSnapshot
import de.arjmandi.nanobot.companion.attachment.AttachmentDriving
import de.arjmandi.nanobot.companion.attachment.AttachmentViewModel
import de.arjmandi.nanobot.companion.attachment.EmptyAttachmentDriver
import de.arjmandi.nanobot.companion.composer.ComposerViewModel
import de.arjmandi.nanobot.companion.voice.AudioRecording
import de.arjmandi.nanobot.companion.voice.EmptyVoiceDriver
import de.arjmandi.nanobot.companion.voice.VoiceDriving
import de.arjmandi.nanobot.companion.voice.VoiceViewModel
import de.arjmandi.nanobot.companion.states.StatesViewModel
import de.arjmandi.nanobot.companion.voice.WavRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The chat screen's state and its two rules (plan.md A3, the Android twin of iOS's
 * `ChatViewModel`): the optimistic bubble is on screen before the gateway answers and is marked
 * pending until it does, and the primary button is Stop only while a turn is running and there is
 * nothing new to send — the WebUI's own rule, `showStopButton = isStreaming && !canSend`.
 *
 * The thread itself is the core's. This reads the driver's flow rather than copying it, so there is
 * one thread in the app and no second place for it to drift (plan.md §10, "no hidden state").
 */
class ChatViewModel(
    private val chat: ChatDriver,
    composerDriver: ComposerDriving = ChatComposerAdapter(chat),
    /**
     * The composer's own state, as the bridge publishes it. A chat with no gateway behind it
     * (a test, the debug menu) passes nothing and the model keeps its default snapshot.
     */
    snapshots: StateFlow<ComposerSnapshot>? = null,
    attachmentDriver: AttachmentDriving = EmptyAttachmentDriver(),
    voiceDriver: VoiceDriving = EmptyVoiceDriver(),
    recorder: () -> AudioRecording = { WavRecorder() },
    /**
     * The banner's "Try again" (A8). Injected rather than built here: the reconnect belongs to the
     * shell, which is the only place that knows the stack and the chat on screen.
     */
    onRetryConnection: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + uiDispatcher()),
) {

    /**
     * Whether the thread follows its own tail. The screen measures and calls [scrolled]; the rule
     * is [ThreadViewport]'s (webui `ThreadViewport.tsx:1002`), so it is tested off a device.
     */
    var viewport: ThreadViewport by mutableStateOf(ThreadViewport())
        private set

    /** The reader's scroll settled [distanceFromBottomPx] from the end of the list. */
    fun scrolled(distanceFromBottomPx: Int) {
        viewport = viewport.scrolled(distanceFromBottomPx)
    }

    /** The scroll-to-latest control: the reader asked for the tail, so the stream follows again. */
    fun jumpToLatest() {
        viewport = viewport.pinned()
    }

    /** What nanobot did on the way to each answer (A4), folded per turn. */
    val activity: ActivityViewModel = ActivityViewModel()

    /** The chips, the ring and the one button of the composer row (A5). */
    val composer: ComposerViewModel = ComposerViewModel(composerDriver, scope = scope)

    /** The cards, the lightbox and the upload's typed refusals (A6). */
    val attachments: AttachmentViewModel = AttachmentViewModel(attachmentDriver)

    /** Hold-to-record, tap-to-dictate and the "Transcribing…" state (A6). */
    val voice: VoiceViewModel = VoiceViewModel(recorder(), voiceDriver, scope)

    /** The banner, the notices, the goal strip, the dock, the delivery retry and the snackbar (A8). */
    val states: StatesViewModel = StatesViewModel(
        onResend = { text ->
            composer.draft = text
            scope.launch { send() }
        },
        onRetryConnection = onRetryConnection,
    )

    private val observing: Job? = snapshots?.let { flow ->
        scope.launch {
            flow.collect { snapshot ->
                composer.update(snapshot)
                // The gateway's own numbers bound what the picker may send (C3.7).
                attachments.limits(snapshot.limits)
            }
        }
    }

    val thread: StateFlow<ChatThreadView> = chat.thread

    /**
     * The field's text. It is the composer's, not a copy of it: a draft that a refused send gives
     * back and a draft the queued chip takes away are the same value (A5).
     */
    var draft: String
        get() = composer.draft
        set(value) {
            composer.draft = value
        }

    /** The sentence a refused send shows, as a resource id (A8 turns it into a snackbar). */
    val lastRefusalRes: Int? get() = composer.lastRefusalRes

    val turns: List<ChatTurnView> get() = thread.value.turns

    val isStreaming: Boolean get() = turns.any { it.running }

    /** Typed text is what makes the button Send again: pressing it queues a steer, it does not stop the turn. */
    val canSend: Boolean get() = composer.canSend

    val showsStop: Boolean get() = isStreaming && !canSend

    val isEmpty: Boolean get() = turns.isEmpty()

    /** The drawer's row, or the greeting's chat when nothing is picked: the core replays the history (D46). */
    suspend fun open(chatId: String?) {
        clearRefusal()
        chat.open(chatId)
    }

    /**
     * The optimistic bubble is not invented here: the core's reducer adds it, and this only
     * forwards what the send produced (D9 — one reducer, two feeds).
     */
    suspend fun send() {
        if (isStreaming && !canSend) return
        // A prompt is a request to watch its answer arrive, whatever the reader was reading.
        viewport = viewport.pinned()
        val streamingBefore = isStreaming
        val sending = composer.draft.trim()
        composer.send()
        if (!streamingBefore && composer.lastRefusalRes != null) {
            // A message the gateway refused is a delivery the user can retry, not a silent loss.
            states.deliveryFailed(sending)
        }
        // A message the core took carries the uploads and clears them; the cards go with it.
        if (!streamingBefore && composer.lastRefusalRes == null) attachments.clear()
    }

    /** The transcript lands after whatever was typed; the core appends it to the draft (C6.5). */
    /** The dock's stop control: one command, and a refusal leaves the task on screen (A8). */
    suspend fun cancelSubagent(id: String) {
        chat.cancelSubagent(id)
    }

    fun appendTranscript() {
        val text = voice.lastTranscript ?: return
        composer.draft += (if (composer.draft.isEmpty()) "" else " ") + text
    }

    suspend fun stop() {
        composer.stop()
    }

    /** What the primary button does: stop while streaming with an empty field, send otherwise. */
    suspend fun primaryAction() {
        if (showsStop) stop() else send()
    }

    fun clearRefusal() {
        composer.clearRefusal()
    }

    /**
     * A new thread value: the clusters follow it. A composable calls this from an effect on the
     * thread it already collects, so there is one collector and no second subscription here.
     */
    fun onThread(view: ChatThreadView) {
        activity.update(view.turns)
        states.update(notices = view.notices, subagents = view.subagents, nowMs = System.currentTimeMillis())
        // The one button is Stop only while a turn runs, so the composer learns that here rather
        // than subscribing to the thread a second time.
        composer.update(streaming = isStreaming)
    }

    /** The scope's dispatcher, with the host JVM's answer when no main looper exists. */
    private companion object {
        fun uiDispatcher() = runCatching { Dispatchers.Main.immediate }.getOrDefault(Dispatchers.Default)
    }
}
