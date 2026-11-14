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
    /** True until `message_accepted` proves the gateway took it (C4.5). */
    public val optimistic: Boolean = false,
    public val createdAtMs: Long? = null,
)

public data class AnswerState(
    public val segments: List<StreamSegment> = emptyList(),
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

public enum class TurnStatus { Running, Complete, Failed }

/** User-facing states that are not part of a turn's answer (C7.3). */
public sealed interface Notice {
    public val id: String

    public data class Compaction(override val id: String, public val phase: String) : Notice

    public data class Recovery(override val id: String, public val canContinue: Boolean, public val reason: String?) : Notice

    public data class Retry(
        override val id: String,
        public val attempt: Int,
        public val maxAttempts: Int?,
        public val errorKind: String?,
    ) : Notice

    public data class Goal(override val id: String, public val active: Boolean) : Notice

    public data class Failure(override val id: String, public val errorKind: String?, public val message: String?) : Notice

    /** A different model answered than the preset asked for (C6.2, `turn_model_updated.fallback`). */
    public data class PresetFallback(override val id: String, public val modelName: String?) : Notice
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
