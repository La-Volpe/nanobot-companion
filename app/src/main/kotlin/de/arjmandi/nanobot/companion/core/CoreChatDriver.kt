package de.arjmandi.nanobot.companion.core

import de.arjmandi.nanobot.companion.activity.ActivityItemView
import de.arjmandi.nanobot.companion.activity.ActivityKind
import de.arjmandi.nanobot.companion.activity.ToolRunStatus
import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.chat.ChatThreadView
import de.arjmandi.nanobot.companion.chat.ChatTurnView
import de.arjmandi.nanobot.companion.chat.ThreadHistory
import de.arjmandi.nanobot.companion.facade.ChatHandle
import de.arjmandi.nanobot.companion.facade.markdownDocument
import de.arjmandi.nanobot.companion.markdown.MdAlignment
import de.arjmandi.nanobot.companion.media.MediaKindView
import de.arjmandi.nanobot.companion.media.MediaRow
import de.arjmandi.nanobot.companion.projection.MediaAttachment
import de.arjmandi.nanobot.companion.projection.MediaKind
import de.arjmandi.nanobot.companion.markdown.MdBlock
import de.arjmandi.nanobot.companion.markdown.MdInline
import de.arjmandi.nanobot.companion.markdown.MdListItem
import de.arjmandi.nanobot.companion.markdown.MarkdownView
import de.arjmandi.nanobot.companion.projection.Alignment
import de.arjmandi.nanobot.companion.projection.Inline
import de.arjmandi.nanobot.companion.projection.ListItem
import de.arjmandi.nanobot.companion.projection.MarkdownBlock
import de.arjmandi.nanobot.companion.projection.MarkdownDocument
import de.arjmandi.nanobot.companion.facade.OutboundDraft
import de.arjmandi.nanobot.companion.facade.SendReceipt
import de.arjmandi.nanobot.companion.projection.ActivityItem
import de.arjmandi.nanobot.companion.projection.Notice
import de.arjmandi.nanobot.companion.projection.SandboxIssue
import de.arjmandi.nanobot.companion.projection.Subagent
import de.arjmandi.nanobot.companion.states.SandboxIssue as SandboxIssueView
import de.arjmandi.nanobot.companion.states.SubagentRow
import de.arjmandi.nanobot.companion.states.ThreadNotice
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.ToolStatus
import de.arjmandi.nanobot.companion.projection.Turn
import de.arjmandi.nanobot.companion.projection.TurnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * One chat, as the app drives it. Reads the handle's thread and maps it to what Android draws;
 * every command forwards to the facade and nothing branches here. The reducer's decisions are the
 * core's, which is why a replayed history and a live turn produce the same rows.
 */
class CoreChatDriver(
    private val handle: ChatHandle,
    scope: CoroutineScope,
    /**
     * Where the gateway lives, so the signed `/api/media/<sig>/<payload>` path can be made
     * loadable. Media is signature-authenticated (`nanobot/webui/media_api.py:207`), so the url
     * needs an origin and no token.
     */
    private val mediaBase: String = "",
) : ChatDriver {

    override val thread: StateFlow<ChatThreadView> = combine(handle.thread, handle.history) { thread, history ->
        thread.toView(history.toView(), mediaBase)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        handle.thread.value.toView(handle.history.value.toView(), mediaBase),
    )

    override suspend fun open(chatId: String?) {
        handle.open()
    }

    override suspend fun send(text: String): String? =
        when (val receipt = handle.send(OutboundDraft(text = text))) {
            is SendReceipt.Accepted -> receipt.turnId
            is SendReceipt.Rejected -> null
        }

    override suspend fun stop() {
        handle.stop()
    }

    override suspend fun cancelSubagent(id: String) {
        handle.cancelSubagent(taskId = id)
    }
}

/**
 * `ThreadState` → the value the thread draws. The core keeps the segments, the status and the
 * notices; this only names what a row needs, so a screen never sees a reducer type.
 */
internal fun de.arjmandi.nanobot.companion.facade.HistoryStatus.toView(): ThreadHistory = when (this) {
    de.arjmandi.nanobot.companion.facade.HistoryStatus.Loading -> ThreadHistory.Loading
    de.arjmandi.nanobot.companion.facade.HistoryStatus.Loaded -> ThreadHistory.Loaded
    de.arjmandi.nanobot.companion.facade.HistoryStatus.Failed -> ThreadHistory.Failed
}

internal fun ThreadState.toView(
    history: ThreadHistory = ThreadHistory.Loaded,
    mediaBase: String = "",
): ChatThreadView = ChatThreadView(
    turns = turns.map { it.toView(mediaBase) },
    notices = notices.mapNotNull(Notice::toView),
    subagents = subagents.map(Subagent::toView),
    history = history,
)

internal fun Turn.toView(mediaBase: String = ""): ChatTurnView {
    val segments = answer.segments
    // A turn streams while it runs and its last segment is still open.
    val streaming = status == TurnStatus.Running && segments.lastOrNull()?.closed == false
    return ChatTurnView(
        id = id,
        prompt = prompt?.text,
        promptPending = prompt?.optimistic == true,
        answer = segments.joinToString("") { it.text },
        answerStreaming = streaming,
        running = status == TurnStatus.Running,
        activity = activity.mapNotNull(ActivityItem::toView),
        startedAtMs = startedAtMs,
        blocks = MarkdownView(markdownDocument(answer.text, streaming).blocks.map(MarkdownBlock::toView)),
        media = answer.media.map { it.toView(mediaBase) },
        promptMedia = prompt?.media.orEmpty().map { it.toView(mediaBase) },
    )
}

/** `MediaAttachment` (the core's row) → what the thread draws, with a url the loader can open. */
internal fun MediaAttachment.toView(mediaBase: String): MediaRow = MediaRow(
    id = id,
    name = name,
    url = absoluteUrl(mediaBase).takeIf { it.isNotBlank() },
    kind = when (kind) {
        MediaKind.Image -> MediaKindView.Image
        MediaKind.Video -> MediaKindView.Video
        MediaKind.Audio -> MediaKindView.Audio
        MediaKind.File -> MediaKindView.File
    },
)

/**
 * `ActivityItem` is a sealed interface, so the four cases are four branches and nothing else: an
 * item this build does not know is dropped rather than drawn as a blank row.
 */
internal fun ActivityItem.toView(): ActivityItemView? = when (this) {
    is ActivityItem.Reasoning -> ActivityItemView(id, ActivityKind.Reasoning(text = text, done = done))
    is ActivityItem.Tool -> ActivityItemView(
        id,
        ActivityKind.Tool(name = name, summary = summary, status = status.toView(), detail = detail),
    )

    is ActivityItem.FileEdit -> ActivityItemView(
        id,
        ActivityKind.FileEdit(
            path = path,
            status = status.toView(),
            added = added,
            deleted = deleted,
            operation = operation,
            diff = diff.orEmpty(),
        ),
    )

    is ActivityItem.Search -> ActivityItemView(id, ActivityKind.Search(query = query, sources = sources))
}

internal fun ToolStatus.toView(): ToolRunStatus = when (this) {
    ToolStatus.Running -> ToolRunStatus.Running
    ToolStatus.Done -> ToolRunStatus.Done
    ToolStatus.Failed -> ToolRunStatus.Failed
}

/**
 * `MarkdownDocument` → the tree the chat draws. The core parses and both platforms render, so
 * this is a rename and nothing else: an unknown block or inline drops rather than drawing blank.
 */
internal fun MarkdownBlock.toView(): MdBlock = when (this) {
    is MarkdownBlock.Heading -> MdBlock.Heading(level, inlines.mapNotNull(Inline::toView))
    is MarkdownBlock.Paragraph -> MdBlock.Paragraph(inlines.mapNotNull(Inline::toView))
    is MarkdownBlock.ListBlock -> MdBlock.Bullets(
        ordered = ordered,
        start = start,
        items = items.map { it.toView() },
    )

    is MarkdownBlock.CodeFence -> MdBlock.Code(language = language, code = code, closed = closed)
    is MarkdownBlock.Table -> MdBlock.Table(
        header = header.map { cell -> cell.mapNotNull(Inline::toView) },
        alignments = alignments.map { it.toView() },
        rows = rows.map { row -> row.map { cell -> cell.mapNotNull(Inline::toView) } },
    )

    is MarkdownBlock.Quote -> MdBlock.Quote(blocks.map(MarkdownBlock::toView))
    MarkdownBlock.Rule -> MdBlock.Rule
}

internal fun ListItem.toView(): MdListItem =
    MdListItem(depth = depth, inlines = inlines.mapNotNull(Inline::toView), checked = checked)

internal fun Alignment.toView(): MdAlignment = when (this) {
    Alignment.None -> MdAlignment.None
    Alignment.Left -> MdAlignment.Left
    Alignment.Center -> MdAlignment.Center
    Alignment.Right -> MdAlignment.Right
}

internal fun Inline.toView(): MdInline = when (this) {
    is Inline.Text -> MdInline.Text(text)
    is Inline.Code -> MdInline.Code(text)
    is Inline.Emphasis -> MdInline.Emphasis(inlines.mapNotNull(Inline::toView))
    is Inline.Strong -> MdInline.Strong(inlines.mapNotNull(Inline::toView))
    is Inline.Strikethrough -> MdInline.Strikethrough(inlines.mapNotNull(Inline::toView))
    is Inline.Link -> MdInline.Link(inlines.mapNotNull(Inline::toView), destination)
}

/**
 * `Notice` → the row the screen draws. Seven casts and nothing else: a notice this build does
 * not know is dropped rather than drawn as a blank row.
 */
internal fun Notice.toView(): ThreadNotice? = when (this) {
    is Notice.Compaction -> ThreadNotice.Compaction(id = id, phase = phase, reason = reason)
    is Notice.Recovery -> ThreadNotice.Recovery(id = id, status = status, canContinue = canContinue, reason = reason)
    is Notice.Retry -> ThreadNotice.Retry(
        id = id,
        attempt = attempt,
        maxAttempts = maxAttempts,
        errorKind = errorKind,
        retryAtMs = retryAtMs,
    )

    is Notice.Goal -> ThreadNotice.Goal(id = id, active = active, status = status, summary = summary)
    is Notice.Failure -> ThreadNotice.Failure(id = id, errorKind = errorKind, message = message)
    is Notice.PresetFallback -> ThreadNotice.PresetFallback(id = id, modelName = modelName)
    is Notice.Sandbox -> ThreadNotice.Sandbox(id = id, issue = issue.toView())
}

internal fun SandboxIssue.toView(): SandboxIssueView = when (this) {
    SandboxIssue.LauncherMissing -> SandboxIssueView.LauncherMissing
    SandboxIssue.Path -> SandboxIssueView.Path
    SandboxIssue.Permissions -> SandboxIssueView.Permissions
    SandboxIssue.Startup -> SandboxIssueView.Startup
}

internal fun Subagent.toView(): SubagentRow = SubagentRow(
    id = id,
    label = label,
    state = state,
    iteration = iteration,
    elapsedSeconds = elapsedSeconds,
)
