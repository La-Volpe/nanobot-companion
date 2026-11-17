package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.projection.Effect
import de.arjmandi.nanobot.companion.projection.Notice
import de.arjmandi.nanobot.companion.projection.ThreadReducer
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.TurnControl
import de.arjmandi.nanobot.companion.projection.TurnControlState
import de.arjmandi.nanobot.companion.transport.AttachmentPolicy
import de.arjmandi.nanobot.companion.transport.CandidateFile
import de.arjmandi.nanobot.companion.wire.Outbound
import de.arjmandi.nanobot.companion.wire.UploadReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The gateway's own transcription bounds, from `transcription_ws.py`. */
internal const val MIN_TRANSCRIPTION_MS: Long = 650
internal const val MAX_TRANSCRIPTION_MS: Long = 120_000

/** A request the caller got wrong, before any round trip: the gateway's own word for it. */
private const val BAD_REQUEST: Int = 400

/**
 * One chat. The handle is a view over a [ChatSession] the core owns, so two
 * handles for the same chat show the same thread instead of drifting apart.
 */
public class ChatHandle internal constructor(
    private val core: NanobotCore,
    internal val session: ChatSession,
) {

    /** The gateway's chat id, null for a chat the gateway has not minted yet. */
    public val id: StateFlow<String?> get() = session.id

    public val thread: StateFlow<ThreadState> get() = session.thread

    /**
     * Whether the stored page has been read. A thread with no turns is either an untouched
     * conversation or one whose history has not arrived, and the screen needs to tell them apart.
     */
    public val history: StateFlow<HistoryStatus> get() = session.history

    public val composer: StateFlow<ComposerState> get() = session.composer

    /**
     * Open this chat: one `attach` for the live subscription, then the stored turns from the
     * gateway's own thread page, reduced into [thread]. A screen calls this when it appears;
     * calling it again on the same chat is free.
     */
    @Throws(Throwable::class)
    public suspend fun open() {
        val chatId = session.id.value ?: return
        core.openChat(chatId)
    }

    /**
     * A prompt that landed, asked the only way a screen can: is there a bubble for it that was
     * not there before? [mark] is the thread's turn ids from before the attempt, so a second
     * send of the same words is not credited to the first bubble.
     *
     * This exists because a receipt can fail where the message did not: the phone showed the
     * prompt under its own bubble *and* back in the composer, so the restore has to be decided by
     * the thread rather than by whether the call returned.
     */
    public fun promptLanded(text: String, mark: List<String>): Boolean =
        landedTurn(session.thread.value, text, mark) != null

    /** The ids a caller keeps across a send, so `promptLanded` can tell new from old. */
    public fun threadMark(): List<String> = session.thread.value.turns.map { turn -> turn.id }

    @Throws(Throwable::class)
    public suspend fun send(draft: OutboundDraft): SendReceipt {
        val chatId = session.id.value ?: return SendReceipt.Rejected(SendRejection.NotConnected)
        return core.sendDraft(chatId, draft)
    }

    /**
     * A preset switch is the `/model` command on this chat, not a frame of its own.
     *
     * The pick is the chat's own until the gateway answers, which is the WebUI's `localModelPreset`:
     * `/model` comes back as a turn with an ordinary message and **no** `turn_model_updated`
     * (measured against the dev gateway), so the chip has nothing else to name the new preset and
     * a frame in between used to wipe it back. A pick that never reached the socket is a
     * rejection rather than a silence, and the chip goes back to what the chat is really on.
     *
     * The row is mirrored in full rather than the preset alone: a chip that names the new preset
     * while the ring still divides by the old one's window is the half-updated row that read `GLM`
     * over `256K`.
     */
    @Throws(Throwable::class)
    public suspend fun setModelPreset(preset: String): PresetResult {
        val chatId = session.id.value ?: return PresetResult.Rejected(PresetRejection.NotConnected)
        session.pickedPreset = preset
        session.pickAnswered = false
        core.remirrorComposer(session, chatId)
        val written = core.write(Outbound.Message(chatId = chatId, turnId = core.nextTurnId(), content = "/model $preset"))
        if (!written) {
            session.pickedPreset = null
            session.pickAnswered = false
            core.remirrorComposer(session, chatId)
            return PresetResult.Rejected(PresetRejection.NotSent)
        }
        return PresetResult.Sent
    }

    /**
     * The access chip. The gateway answers only by refusing (`detail:
     * "workspace_scope_rejected"`), so the previous mode is remembered here until then.
     */
    @Throws(Throwable::class)
    public suspend fun setWorkspaceScope(mode: WorkspaceScope) {
        val chatId = session.id.value ?: return
        session.scopeBeforeSwitch = session.composer.value.accessMode
        session.composer.value = session.composer.value.copy(accessMode = mode)
        core.write(Outbound.SetWorkspaceScope(chatId = chatId, accessMode = mode.wire))
    }

    /**
     * A picked file goes to the gateway's upload endpoint and comes back as a reference to
     * put on the next message. The size and type checks happen before a byte leaves the
     * device, using `ready.limits` like every other composer limit.
     */
    @Throws(Throwable::class)
    public suspend fun upload(file: PickedFile): UploadResult {
        val limits = session.composer.value.limits
        if (limits != null) {
            val candidate = CandidateFile(name = file.name, mimeType = file.mimeType, bytes = file.bytes.size.toLong())
            val rejection = AttachmentPolicy(limits.attachments).validate(listOf(candidate))
            if (rejection != null) return UploadResult.Rejected(rejection)
        }
        return core.upload(session, file)
    }

    /** The composer's text field; the draft also survives a refused send. */
    public fun setDraft(text: String) {
        session.composer.value = session.composer.value.copy(draft = text)
    }

    /**
     * Type-and-send while a turn runs: the WebUI's `showStopButton = isStreaming &&
     * !canSend` means the Send button queues rather than stops. What is typed while a prompt
     * waits joins it, which is the WebUI's own merge (`useQueuedPrompt.mergeQueuedPrompt`).
     */
    public fun enqueue(text: String) {
        val clean = text.trim()
        val chatId = session.id.value
        if (clean.isEmpty() || chatId == null) return
        session.control = TurnControl.enqueue(session.control, chatId, clean).state
        session.mirrorQueue()
    }

    /**
     * Send now: the queued prompt steers the running turn instead of waiting for it, and goes
     * out as a fresh message when nothing is running (`useQueuedPrompt.send` sets
     * `continueActiveTurn` only while streaming).
     */
    @Throws(Throwable::class)
    public suspend fun sendQueuedNow(): SendReceipt {
        val waiting = session.control.queued.filter { it.chatId == session.id.value }
        if (waiting.isEmpty()) return SendReceipt.Rejected(SendRejection.EmptyDraft)
        val text = waiting.joinToString("\n") { it.text }
        session.control = session.control.copy(queued = session.control.queued - waiting.toSet())
        session.mirrorQueue()

        val chatId = session.id.value ?: return SendReceipt.Rejected(SendRejection.NotConnected)
        val active = session.thread.value.activeTurnId
            ?: return core.sendDraft(chatId, OutboundDraft(text = text))
        val reduction = TurnControl.steer(session.control, chatId, active, text)
        session.control = reduction.state
        val written = reduction.effects.mapNotNull { (it as? Effect.Send)?.frame }.map { core.write(it) }
        return if (written.all { it }) SendReceipt.Accepted(active) else SendReceipt.Rejected(SendRejection.NotConnected)
    }

    /** Delete on the chip: the prompt never leaves the device. */
    public fun discardQueued() {
        session.control = session.control.copy(queued = session.control.queued.filterNot { it.chatId == session.id.value })
        session.mirrorQueue()
    }

    /**
     * One recording, transcribed by the gateway. The transcript lands after whatever
     * was already typed, so a half-written note is not replaced.
     */
    @Throws(Throwable::class)
    public suspend fun transcribe(pcm: ByteArray, durationMs: Long): TranscriptionResult {
        if (durationMs < MIN_TRANSCRIPTION_MS) return TranscriptionResult.Rejected(TranscriptionRejection.TooShort)
        if (durationMs > MAX_TRANSCRIPTION_MS) return TranscriptionResult.Rejected(TranscriptionRejection.TooLong)
        val chatId = session.id.value ?: return TranscriptionResult.Rejected(TranscriptionRejection.NotConnected)
        return core.transcribe(chatId, session, pcm, durationMs)
    }

    /**
     * The recovery notice's two buttons. Both are socket mutations carrying the recovery id,
     * and the gateway's refusal is what comes back: a refused continue leaves the decision on
     * screen rather than pretending the task resumed.
     */
    @Throws(Throwable::class)
    public suspend fun continueRecovery(): AutomationActionResult {
        val chatId = session.id.value ?: return AutomationActionResult.NotConnected
        val notice = session.thread.value.notices.filterIsInstance<Notice.Recovery>().firstOrNull()
            ?: return AutomationActionResult.Refused(BAD_REQUEST, "no_recovery_notice")
        return core.recoveryAction(chatId, notice.id, "recovery.continue")
    }

    @Throws(Throwable::class)
    public suspend fun dismissRecovery(): AutomationActionResult {
        val chatId = session.id.value ?: return AutomationActionResult.NotConnected
        val notice = session.thread.value.notices.filterIsInstance<Notice.Recovery>().firstOrNull()
            ?: return AutomationActionResult.Refused(BAD_REQUEST, "no_recovery_notice")
        return core.recoveryAction(chatId, notice.id, "recovery.dismiss")
    }

    /**
     * The subtask dock's stop control. The gateway's one control is `subagent.cancel` with
     * the session key and the task id (`ws_http.py:931`); a refusal leaves the task on screen.
     */
    @Throws(Throwable::class)
    public suspend fun cancelSubagent(taskId: String): AutomationActionResult {
        val chatId = session.id.value ?: return AutomationActionResult.NotConnected
        if (taskId.isBlank()) return AutomationActionResult.Refused(BAD_REQUEST, "missing_task_id")
        return core.subagentCancel(chatId, taskId)
    }

    /** A notice the client owns, the sandbox warning, goes away when the user says so. */
    public fun dismissNotice(id: String) {
        session.thread.value = ThreadReducer.dismiss(session.thread.value, id)
    }

    /** Stop is the text `/stop` on the running turn; the gateway has no stop frame. */
    @Throws(Throwable::class)
    public suspend fun stop() {
        val chatId = session.id.value ?: return
        val active = session.thread.value.activeTurnId ?: return
        val reduction = TurnControl.stop(session.control, chatId, active)
        session.control = reduction.state
        reduction.effects.mapNotNull { (it as? Effect.Send)?.frame }.forEach { core.write(it) }
    }
}

/** The turn a bubble for [text] became, or null when this thread has no such prompt. */
internal fun landedTurn(thread: ThreadState, text: String, before: List<String>): String? {
    val prompt = text.trim()
    if (prompt.isEmpty()) return null
    return thread.turns
        .firstOrNull { turn -> turn.id !in before && turn.prompt?.text == prompt }
        ?.id
}

/** The mutable half of one chat: a handle is a view of this, never a copy of it. */
internal class ChatSession(id: MutableStateFlow<String?>) {

    val id: MutableStateFlow<String?> = id

    val thread: MutableStateFlow<ThreadState> = MutableStateFlow(ThreadState(chatId = id.value.orEmpty()))

    /**
     * The page read behind [thread]. It starts `Loaded` because nobody has asked for a page: a
     * chat with no id has none to ask for, and a chat just learned about is empty until `open()`
     * puts a read in flight. Starting it `Loading` made a brand-new chat wear the skeleton of a
     * page that was never on its way.
     */
    val history: MutableStateFlow<HistoryStatus> = MutableStateFlow(HistoryStatus.Loaded)

    val composer: MutableStateFlow<ComposerState> = MutableStateFlow(ComposerState())

    var control: TurnControlState = TurnControlState()

    /** The mode to go back to if the gateway refuses the switch. */
    var scopeBeforeSwitch: WorkspaceScope? = null

    /**
     * The preset this chat was last told to switch to, until the gateway names the model it ran
     * (`turn_model_updated`) or the chat's own session row answers for it. `/model` has no frame
     * of its own, so this is what the chip reads in between.
     */
    var pickedPreset: String? = null

    /**
     * Whether the turn the pick became has ended: only then has the gateway committed the preset
     * to the session's metadata, so only then is a drawer read an answer about it.
     */
    var pickAnswered: Boolean = false

    /** What was typed before a recording started; the transcript is appended to it. */
    var draftBeforeRecording: String = ""

    /** Uploads waiting for a message to carry them; cleared when one is accepted. */
    val media: MutableList<UploadReference> = mutableListOf()

    /** The queue as the chip reads it: the prompts waiting for this chat, one per line. */
    internal fun mirrorQueue() {
        val text = control.queued.joinToString("\n") { it.text }
        composer.value = composer.value.copy(queued = text.ifEmpty { null })
    }
}
