package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.Usage

/**
 * What the chat screen renders (plan.md C4.1), as one immutable value. Every field is
 * something a view may show, and the ids are derived from gateway ids rather than from
 * arrival order, so reducing the same frames twice yields the same state.
 */
public data class ThreadState(
    public val chatId: String,
    public val turns: List<Turn> = emptyList(),
    public val activeTurnId: String? = null,
    public val notices: List<Notice> = emptyList(),
    public val usage: Usage? = null,
    public val contextWindowTokens: Int? = null,
    public val modelPreset: String? = null,
    public val modelName: String? = null,
    /** The subtasks this chat's agent is running (`subagent_task`, C7.2's dock in I8). */
    public val subagents: List<Subagent> = emptyList(),
) {
    public val activeTurn: Turn? get() = turns.lastOrNull { it.id == activeTurnId }

    public fun turn(id: String): Turn? = turns.firstOrNull { it.id == id }
}

public data class Turn(
    public val id: String,
    public val prompt: UserPrompt? = null,
    public val answer: AnswerState = AnswerState(),
    public val activity: List<ActivityItem> = emptyList(),
    public val status: TurnStatus = TurnStatus.Running,
    public val usage: Usage? = null,
    public val failureKind: String? = null,
    /** The local wall clock when the prompt was composed; the gateway's time is separate. */
    public val startedAtMs: Long? = null,
)

public data class UserPrompt(
    public val text: String,
    public val quote: String? = null,
    /** What was sent with it (`user_message.media_urls`), drawn above the text. */
    public val media: List<MediaAttachment> = emptyList(),
    /** True until `message_accepted` proves the gateway took it (C4.5). */
    public val optimistic: Boolean = false,
    public val createdAtMs: Long? = null,
)

public data class AnswerState(
    public val segments: List<StreamSegment> = emptyList(),
    /** The pictures, clips and recordings the answer carries (`message.media_urls`). */
    public val media: List<MediaAttachment> = emptyList(),
    /** Reasoning is streamed in parallel with the answer and collapses when it ends (C4.3). */
    public val reasoning: List<StreamSegment> = emptyList(),
    public val reasoningDone: Boolean = false,
) {
    public val text: String get() = segments.joinToString("") { it.text }
}

public data class StreamSegment(
    public val id: String,
    public val text: String = "",
    public val closed: Boolean = false,
)

public sealed interface ActivityItem {
    public val id: String

    public data class Reasoning(override val id: String, public val text: String, public val done: Boolean) : ActivityItem

    public data class Tool(
        override val id: String,
        public val name: String,
        public val summary: String?,
        public val status: ToolStatus,
        public val detail: String? = null,
    ) : ActivityItem

    /** A write or edit, rendered as a path with a unified diff (C4.4). */
    public data class FileEdit(
        override val id: String,
        public val path: String,
        public val status: ToolStatus,
        public val added: Int = 0,
        public val deleted: Int = 0,
        public val operation: String? = null,
        public val diff: String? = null,
    ) : ActivityItem

    public data class Search(override val id: String, public val query: String, public val sources: List<String>) : ActivityItem
}

public enum class ToolStatus { Running, Done, Failed }

/**
 * One subtask the parent agent owns (`nanobot/agent/subagent_status.py`). The gateway sends the
 * whole snapshot on every revision and stops once it no longer owns the task, so this is a
 * value the dock replaces rather than a log it appends to.
 */
public data class Subagent(
    public val id: String,
    public val label: String,
    public val description: String?,
    public val state: String,
    public val iteration: Int,
    public val elapsedSeconds: Double,
    public val originTurnId: String?,
) {
    /** `queued`, `running` and `stopping` are the states the dock shows; the rest are settled. */
    public val isActive: Boolean get() = state in ACTIVE_STATES

    internal companion object {
        val ACTIVE_STATES: Set<String> = setOf("queued", "running", "stopping")
    }
}

public enum class TurnStatus { Running, Complete, Failed }

/** User-facing states that are not part of a turn's answer (C7.3). */
public sealed interface Notice {
    public val id: String

    /**
     * One line per compaction: the phase of the *same* `compaction_id` replaces the row
     * rather than adding a second, because one compaction is one event in the transcript
     * (`ContextCompactionNotice.tsx`, `phase: started | succeeded | failed | cancelled`).
     */
    public data class Compaction(
        override val id: String,
        public val phase: String,
        public val reason: String? = null,
    ) : Notice

    /**
     * A task the host interrupted. It is offered only while the gateway is waiting for a
     * decision — `awaiting_user` or `failed` — and `canContinue` is the gateway's own answer
     * to whether the saved context could be restored (`RecoveryNotice.tsx:41`).
     */
    public data class Recovery(
        override val id: String,
        public val status: String,
        public val canContinue: Boolean,
        public val reason: String? = null,
        public val failureErrorKind: String? = null,
        public val replyPreserved: Boolean = false,
    ) : Notice

    /**
     * A retry with a clock on it: `retryAtMs` is the frame's `retry_after_s` added to the
     * clock the reducer was given, so the countdown is derived, never a timer in the core.
     */
    public data class Retry(
        override val id: String,
        public val attempt: Int,
        public val maxAttempts: Int?,
        public val errorKind: String?,
        public val retryAtMs: Long?,
    ) : Notice

    /** The goal strip: one line, the gateway's summary or its objective (`ThreadComposer.tsx:515`). */
    public data class Goal(
        override val id: String,
        public val active: Boolean,
        public val status: String? = null,
        public val summary: String? = null,
    ) : Notice

    public data class Failure(override val id: String, public val errorKind: String?, public val message: String?) : Notice

    /** A different model answered than the preset asked for (C6.2, `turn_model_updated.fallback`). */
    public data class PresetFallback(override val id: String, public val modelName: String?) : Notice

    /**
     * Shell tools are paused because the sandbox could not run them. The four issues are the
     * seven `exec` error codes collapsed to what a person can act on (`useSandboxNotice.ts:5`).
     */
    public data class Sandbox(override val id: String, public val issue: SandboxIssue) : Notice
}

/** What went wrong with the sandbox, as the notice sentence is chosen by (copy keys, not copy). */
public enum class SandboxIssue(public val copyKey: String) {
    /** `sandbox_launcher_missing` — the launcher binary is not installed. */
    LauncherMissing("notice.sandbox.missing"),

    /** `sandbox_path_not_absolute`, `sandbox_not_executable`, `sandbox_path_unavailable`. */
    Path("notice.sandbox.path"),

    /** `sandbox_agent_writable`, `sandbox_unprotected_installation`. */
    Permissions("notice.sandbox.permissions"),

    /** `sandbox_start_failed` — the launcher is there and would not start. */
    Startup("notice.sandbox.startup"),
}

/** Effects are data; the engine executes them (§2). */
internal sealed interface Effect {
    data class PersistCache(val chatId: String) : Effect
    data class SettleDelivery(val turnId: String, val accepted: Boolean) : Effect
    data object ScheduleQueueFlush : Effect

    /** One frame to put on the socket, in the order the reduction produced it. */
    data class Send(val frame: de.arjmandi.nanobot.companion.wire.Outbound) : Effect
}

internal data class Reduction(val state: ThreadState, val effects: List<Effect> = emptyList())
