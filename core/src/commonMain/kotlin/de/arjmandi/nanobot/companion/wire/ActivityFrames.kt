package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.JsonObject

/**
 * Tool rows, diffs and prompts. The gateway reports tool work inside `message.tool_events`
 * and edits inside `file_edit`, so both carry the raw objects the activity list renders
 * from.
 */
internal data class MessageFrame(
    val text: String?,
    val renderAs: String?,
    val media: List<String>,
    val mediaUrls: List<JsonObject>,
    val replyTo: String?,
    val latencyMs: Long?,
    val toolEvents: List<JsonObject>,
    val kind: String?,
    val turnId: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "message"
}

internal data class UserMessageFrame(
    val text: String?,
    /** The attachments the prompt was sent with; `media_paths` is the same files, agent-side. */
    val mediaUrls: List<JsonObject>,
    val createdAtMs: Long?,
    val startsTurn: Boolean,
    val inputId: String?,
    val provenance: JsonObject?,
    val turnId: String?,
    val turnPhase: String?,
    val turnSeq: Int?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "user_message"
}

internal data class FileEditFrame(
    val edits: List<JsonObject>,
    val turnId: String?,
    val turnPhase: String?,
    val turnSeq: Int?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "file_edit"
}

internal data class ModelRequestFrame(
    val requestId: String?,
    val runId: String?,
    val sequence: Int?,
    val phase: String?,
    val requestUsage: Usage?,
    val usage: Usage?,
    val roundUsages: List<Usage>,
    val contextWindowTokens: Int?,
    val turnId: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "model_request"
}

internal data class ContextCompactionFrame(
    val compactionId: String?,
    val phase: String?,
    val reason: String?,
    val turnId: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "context_compaction"
}

internal data class SubagentTaskFrame(
    val task: JsonObject?,
    val turnId: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "subagent_task"
}
