package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.projection.Effect
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.TurnControl
import de.arjmandi.nanobot.companion.projection.TurnControlState
import de.arjmandi.nanobot.companion.transport.AttachmentPolicy
import de.arjmandi.nanobot.companion.transport.CandidateFile
import de.arjmandi.nanobot.companion.wire.Outbound
import de.arjmandi.nanobot.companion.wire.UploadReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The gateway's own transcription bounds, from `transcription_ws.py` (C6.5). */
internal const val MIN_TRANSCRIPTION_MS: Long = 650
internal const val MAX_TRANSCRIPTION_MS: Long = 120_000

/**
 * One chat (plan.md §2). The handle is a view over a [ChatSession] the core owns, so two
 * handles for the same chat show the same thread instead of drifting apart.
 */
public class ChatHandle internal constructor(
    private val core: NanobotCore,
    internal val session: ChatSession,
) {

    /** The gateway's chat id, null for a chat the gateway has not minted yet (C5.3). */
    public val id: StateFlow<String?> get() = session.id

    public val thread: StateFlow<ThreadState> get() = session.thread

    public val composer: StateFlow<ComposerState> get() = session.composer

    public suspend fun send(draft: OutboundDraft): SendReceipt {
        val chatId = session.id.value ?: return SendReceipt.Rejected(SendRejection.NotConnected)
        return core.sendDraft(chatId, draft)
    }

    /** A preset switch is the `/model` command on this chat, not a frame of its own (C6.2). */
    public suspend fun setModelPreset(preset: String) {
        val chatId = session.id.value ?: return
        core.write(Outbound.Message(chatId = chatId, turnId = core.nextTurnId(), content = "/model $preset"))
    }

    /**
     * The access chip. The gateway answers only by refusing — `detail:
     * "workspace_scope_rejected"` — so the previous mode is remembered here until then (C6.3).
     */
    public suspend fun setWorkspaceScope(mode: WorkspaceScope) {
        val chatId = session.id.value ?: return
        session.scopeBeforeSwitch = session.composer.value.accessMode
        session.composer.value = session.composer.value.copy(accessMode = mode)
        core.write(Outbound.SetWorkspaceScope(chatId = chatId, accessMode = mode.wire))
    }

    /**
     * A picked file goes to the gateway's upload endpoint and comes back as a reference to
     * put on the next message (C6.6). The size and type checks happen before a byte leaves the
     * device, using `ready.limits` like every other composer limit (C3.7).
     */
    public suspend fun upload(file: PickedFile): UploadResult {
        val limits = session.composer.value.limits
        if (limits != null) {
            val candidate = CandidateFile(name = file.name, mimeType = file.mimeType, bytes = file.bytes.size.toLong())
            val rejection = AttachmentPolicy(limits.attachments).validate(listOf(candidate))
            if (rejection != null) return UploadResult.Rejected(rejection)
        }
        return core.upload(session, file)
    }

    /** The composer's text field; the draft also survives a refused send (C6.1). */
    public fun setDraft(text: String) {
        session.composer.value = session.composer.value.copy(draft = text)
    }

    /**
     * One recording, transcribed by the gateway (C6.5). The transcript lands after whatever
     * was already typed, so a half-written note is not replaced.
     */
    public suspend fun transcribe(pcm: ByteArray, durationMs: Long): TranscriptionResult {
        if (durationMs < MIN_TRANSCRIPTION_MS) return TranscriptionResult.Rejected(TranscriptionRejection.TooShort)
        if (durationMs > MAX_TRANSCRIPTION_MS) return TranscriptionResult.Rejected(TranscriptionRejection.TooLong)
        val chatId = session.id.value ?: return TranscriptionResult.Rejected(TranscriptionRejection.NotConnected)
        return core.transcribe(chatId, session, pcm, durationMs)
    }

    /** Stop is the text `/stop` on the running turn; the gateway has no stop frame (C5.5). */
    public suspend fun stop() {
        val chatId = session.id.value ?: return
        val active = session.thread.value.activeTurnId ?: return
        val reduction = TurnControl.stop(session.control, chatId, active)
        session.control = reduction.state
        reduction.effects.mapNotNull { (it as? Effect.Send)?.frame }.forEach { core.write(it) }
    }
}

/** The mutable half of one chat: a handle is a view of this, never a copy of it. */
internal class ChatSession(id: MutableStateFlow<String?>) {

    val id: MutableStateFlow<String?> = id

    val thread: MutableStateFlow<ThreadState> = MutableStateFlow(ThreadState(chatId = id.value.orEmpty()))

    val composer: MutableStateFlow<ComposerState> = MutableStateFlow(ComposerState())

    var control: TurnControlState = TurnControlState()

    /** The mode to go back to if the gateway refuses the switch (C6.3). */
    var scopeBeforeSwitch: WorkspaceScope? = null

    /** What was typed before a recording started; the transcript is appended to it (C6.5). */
    var draftBeforeRecording: String = ""

    /** Uploads waiting for a message to carry them; cleared when one is accepted (C6.6). */
    val media: MutableList<UploadReference> = mutableListOf()
}
