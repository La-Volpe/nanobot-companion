package de.arjmandi.nanobot.companion.chat

import de.arjmandi.nanobot.companion.activity.ActivityItemView
import de.arjmandi.nanobot.companion.composer.ComposerDriving
import de.arjmandi.nanobot.companion.composer.PaletteSheet
import de.arjmandi.nanobot.companion.composer.WorkspaceAccess
import de.arjmandi.nanobot.companion.markdown.MarkdownView
import de.arjmandi.nanobot.companion.markdown.MdBlock
import de.arjmandi.nanobot.companion.markdown.MdInline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One turn as the thread draws it: the prompt above, what nanobot did about it, the answer below. */
data class ChatTurnView(
    val id: String,
    val prompt: String?,
    val promptPending: Boolean,
    val answer: String,
    val answerStreaming: Boolean,
    val running: Boolean,
    /** What nanobot thought, ran and changed on the way to the answer (A4), drawn as one cluster. */
    val activity: List<ActivityItemView> = emptyList(),
    /** The core's local compose time, which is the only start time a turn has. */
    val startedAtMs: Long? = null,
    /** The answer as the core parsed it (A5); empty means the row falls back to one paragraph. */
    val blocks: MarkdownView = MarkdownView(emptyList()),
) {
    /** What the row draws: the parsed document, or the text as one paragraph when there is none. */
    val readingBlocks: MarkdownView
        get() = if (blocks.blocks.isNotEmpty() || answer.isEmpty()) {
            blocks
        } else {
            MarkdownView(listOf(MdBlock.Paragraph(listOf(MdInline.Text(answer)))))
        }
}

/**
 * Whether the stored page has been read (D46). The socket's `attach` carries no history, so a
 * thread with no rows is either a conversation nobody has used or one whose page has not
 * arrived — and the screen must draw those two differently.
 */
enum class ThreadHistory { Loading, Loaded, Failed }

/** What an empty thread draws: the read in flight, the read that failed, or a genuinely empty chat. */
enum class ThreadPlaceholder { None, Skeleton, Failed, Empty }

/** What the thread draws (A3; A4 adds the activity rows on top, A8 the notices and the dock). */
data class ChatThreadView(
    val turns: List<ChatTurnView> = emptyList(),
    /** The notices the core reduced (C7.3), owned by `StatesViewModel` on screen. */
    val notices: List<de.arjmandi.nanobot.companion.states.ThreadNotice> = emptyList(),
    /** The subtasks this chat's agent owns (A8's dock). */
    val subagents: List<de.arjmandi.nanobot.companion.states.SubagentRow> = emptyList(),
    /**
     * The page read's state. A driver with no socket behind it starts `Loaded`: it has nothing to
     * load, so its empty thread really is empty.
     */
    val history: ThreadHistory = ThreadHistory.Loaded,
)

/**
 * One chat as the screen drives it (plan.md A3). The core owns the thread and the reducer; this is
 * the view of it a view model can hold without a socket, which is what lets the streaming, the
 * button rule and the optimistic bubble be tested off-device.
 */
interface ChatDriver {
    val thread: StateFlow<ChatThreadView>

    /** Open the chat: the core attaches and replays the stored history (D46). */
    suspend fun open(chatId: String?)

    /** Returns the turn id the core accepted, or null when the send was refused. */
    suspend fun send(text: String): String?

    suspend fun stop()

    /** The subtask dock's stop control (A8). A driver with no socket answers nothing. */
    suspend fun cancelSubagent(id: String) = Unit
}

/**
 * The composer's commands over a plain [ChatDriver]: what a chat has without the facade's queue
 * and palettes. The screen's real half is `CoreComposerDriver`; this is what keeps one send path
 * in the model whether a test drives it or the app does.
 */
class ChatComposerAdapter(private val chat: ChatDriver) : ComposerDriving {
    override suspend fun sendPrompt(text: String): Boolean = chat.send(text) != null

    override fun enqueue(text: String) = Unit

    override suspend fun stop() {
        chat.stop()
    }

    override suspend fun sendQueuedNow() = Unit

    override fun discardQueued() = Unit

    override suspend fun setModelPreset(preset: String) = Unit

    override suspend fun setWorkspaceScope(access: WorkspaceAccess) = Unit

    override suspend fun loadPalette(text: String, cursor: Int): PaletteSheet? = null
}

/** A driver with no socket behind it: the screen's initial state (used until a chat is opened). */
class EmptyChatDriver : ChatDriver {
    private val empty = MutableStateFlow(ChatThreadView())

    override val thread: StateFlow<ChatThreadView> = empty.asStateFlow()

    override suspend fun open(chatId: String?) = Unit

    override suspend fun send(text: String): String? = null

    override suspend fun stop() = Unit
}
