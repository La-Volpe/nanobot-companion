package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.JsonObject

/** `message_accepted` reconciles the optimistic bubble with the server's turn id (C4.5). */
internal data class MessageAcceptedFrame(
    val turnId: String?,
    val startsTurn: Boolean,
    val activeTurnId: String?,
    val startedAt: Double?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "message_accepted"
}

internal data class SessionUpdatedFrame(
    val scope: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "session_updated"
}

/** The model that answered this turn, which is what the composer's preset chip shows (C6.2). */
internal data class TurnModelUpdatedFrame(
    val modelName: String?,
    val modelPreset: String?,
    val contextWindowTokens: Int?,
    val fallback: Boolean,
    val reauthProvider: String?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "turn_model_updated"
}

internal data class GoalStatusFrame(
    val status: String?,
    val startedAt: Double?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "goal_status"
}

internal data class GoalStateFrame(
    val goalState: JsonObject?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "goal_state"
}

internal data class RetryStatusFrame(
    val state: String?,
    val attempt: Int?,
    val maxAttempts: Int?,
    val errorKind: String?,
    val retryAfterSeconds: Double?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "retry_status"
}

internal data class RecoveryStateFrame(
    val status: String?,
    val recoveryId: String?,
    val attempts: Int?,
    val reason: String?,
    val canContinue: Boolean,
    val failureErrorKind: String?,
    val replyPreserved: Boolean,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "recovery_state"
}

/** The turn's settle frame: usage for the ring, `goal_state` for the strip, failure fields for retry (C4.5). */
internal data class TurnEndFrame(
    val turnId: String?,
    val latencyMs: Long?,
    val usage: Usage,
    val roundUsages: List<Usage>,
    val contextWindowTokens: Int?,
    val outcome: String?,
    val failureKind: String?,
    val failureErrorKind: String?,
    val failureAttempts: Int?,
    val failureMessage: String?,
    val goalState: GoalSummary,
    val turnPhase: String?,
    val turnSeq: Int?,
    override val chatId: String?,
    override val raw: JsonObject,
) : Frame {
    override val event: String get() = "turn_end"
}

/** D15.4: every one of these arrives inside `turn_end.usage` and `model_request.request_usage`. */
internal data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val requestCount: Int = 0,
    val estimatedTokens: Int = 0,
    val contextTokens: Int = 0,
    val cachedTokens: Int = 0,
    val generationMs: Long = 0,
    val measuredCompletionTokens: Int = 0,
    val ttftMs: Long = 0,
    val timedRequests: Int = 0,
) {
    internal companion object {
        fun of(json: JsonObject?): Usage = json?.let {
            Usage(
                promptTokens = it.integer("prompt_tokens") ?: 0,
                completionTokens = it.integer("completion_tokens") ?: 0,
                totalTokens = it.integer("total_tokens") ?: 0,
                requestCount = it.integer("request_count") ?: 0,
                estimatedTokens = it.integer("estimated_tokens") ?: 0,
                contextTokens = it.integer("context_tokens") ?: 0,
                cachedTokens = it.integer("cached_tokens") ?: 0,
                generationMs = it.long("generation_ms") ?: 0,
                measuredCompletionTokens = it.integer("measured_completion_tokens") ?: 0,
                ttftMs = it.long("ttft_ms") ?: 0,
                timedRequests = it.integer("timed_requests") ?: 0,
            )
        } ?: Usage()
    }
}

/** `{"active": false}` today; the goal strip reads whatever else the gateway adds. */
internal data class GoalSummary(val active: Boolean) {
    internal companion object {
        fun of(json: JsonObject?): GoalSummary = GoalSummary(active = json?.flag("active") ?: false)
    }
}
