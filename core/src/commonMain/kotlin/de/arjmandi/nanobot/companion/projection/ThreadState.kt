package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.Usage

/**
 * What the chat screen renders (plan.md C4.1), as one immutable value. Every field is
 * something a view may show, and the ids are derived from gateway ids rather than from
 * arrival order, so reducing the same frames twice yields the same state.
 */
internal data class ThreadState(
    val chatId: String,
    val turns: List<Turn> = emptyList(),
    val activeTurnId: String? = null,
    val notices: List<Notice> = emptyList(),
    val usage: Usage? = null,
    val contextWindowTokens: Int? = null,
    val modelPreset: String? = null,
    val modelName: String? = null,
) {
    val activeTurn: Turn? get() = turns.lastOrNull { it.id == activeTurnId }

    fun turn(id: String): Turn? = turns.firstOrNull { it.id == id }
}

internal data class Turn(
    val id: String,
    val prompt: UserPrompt? = null,
    val answer: AnswerState = AnswerState(),
    val activity: List<ActivityItem> = emptyList(),
    val status: TurnStatus = TurnStatus.Running,
    val usage: Usage? = null,
    val failureKind: String? = null,
    /** The local wall clock when the prompt was composed; the gateway's time is separate. */
    val startedAtMs: Long? = null,
)

internal data class UserPrompt(
    val text: String,
    val quote: String? = null,
    /** True until `message_accepted` proves the gateway took it (C4.5). */
    val optimistic: Boolean = false,
    val createdAtMs: Long? = null,
)

internal data class AnswerState(
    val segments: List<StreamSegment> = emptyList(),
    /** Reasoning is streamed in parallel with the answer and collapses when it ends (C4.3). */
    val reasoning: List<StreamSegment> = emptyList(),
    val reasoningDone: Boolean = false,
) {
    val text: String get() = segments.joinToString("") { it.text }
}

internal data class StreamSegment(
    val id: String,
    val text: String = "",
    val closed: Boolean = false,
)

internal sealed interface ActivityItem {
    val id: String

    data class Reasoning(override val id: String, val text: String, val done: Boolean) : ActivityItem

    data class Tool(
        override val id: String,
        val name: String,
        val summary: String?,
        val status: ToolStatus,
        val detail: String? = null,
    ) : ActivityItem

    data class FileEdit(override val id: String, val path: String, val status: ToolStatus) : ActivityItem

    data class Search(override val id: String, val query: String, val sources: List<String>) : ActivityItem
}

internal enum class ToolStatus { Running, Done, Failed }

internal enum class TurnStatus { Running, Complete, Failed }

/** User-facing states that are not part of a turn's answer (C7.3). */
internal sealed interface Notice {
    val id: String

    data class Compaction(override val id: String, val phase: String) : Notice

    data class Recovery(override val id: String, val canContinue: Boolean, val reason: String?) : Notice

    data class Retry(override val id: String, val attempt: Int, val maxAttempts: Int?, val errorKind: String?) : Notice

    data class Goal(override val id: String, val active: Boolean) : Notice

    data class Failure(override val id: String, val errorKind: String?, val message: String?) : Notice
}

/** Effects are data; the engine executes them (§2). */
internal sealed interface Effect {
    data class PersistCache(val chatId: String) : Effect
    data class SettleDelivery(val turnId: String, val accepted: Boolean) : Effect
    data object ScheduleQueueFlush : Effect
}

internal data class Reduction(val state: ThreadState, val effects: List<Effect> = emptyList())
