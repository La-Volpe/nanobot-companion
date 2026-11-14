package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Text in, [Frame] out. Nothing here throws on a payload it does not understand: an
 * unknown event or a mis-shaped one becomes [UnknownFrame] with its raw bytes intact,
 * which is what lets a newer gateway talk to this client instead of killing the chat
 * (plan.md C2.2).
 */
internal object FrameCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun decode(text: String): Frame {
        val raw = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return UnknownFrame(event = "unparseable", chatId = null, raw = JsonObject(emptyMap()))

        val chatId = raw.text("chat_id")
        return when (val event = raw.text("event")) {
            "ready" -> ReadyFrame(
                clientId = raw.text("client_id"),
                upload = raw.child("upload")?.let { upload ->
                    val path = upload.text("path") ?: return unknown(event, chatId, raw)
                    val token = upload.text("token") ?: return unknown(event, chatId, raw)
                    UploadCapability(path, token)
                },
                chatId = chatId,
                raw = raw,
            )

            "attached" -> AttachedFrame(chatId = chatId, raw = raw)
            "pairing_changed" -> PairingChangedFrame(chatId = chatId, raw = raw)
            "runtime_model_updated" -> RuntimeModelUpdatedFrame(
                modelName = raw.text("model_name"),
                modelPreset = raw.text("model_preset"),
                chatId = chatId,
                raw = raw,
            )

            "error" -> ErrorFrame(detail = raw.text("detail"), chatId = chatId, raw = raw)

            "webui_response" -> {
                val error = raw.child("error")
                WebuiResponseFrame(
                    requestId = raw.text("request_id"),
                    ok = raw.flag("ok"),
                    result = raw["result"],
                    errorStatus = error?.integer("status"),
                    errorMessage = error?.text("message"),
                    chatId = chatId,
                    raw = raw,
                )
            }

            "transcription_result" -> TranscriptionResultFrame(
                requestId = raw.text("request_id"),
                text = raw.text("text"),
                chatId = chatId,
                raw = raw,
            )

            "transcription_error" -> TranscriptionErrorFrame(
                requestId = raw.text("request_id"),
                detail = raw.text("detail"),
                chatId = chatId,
                raw = raw,
            )

            "delta", "stream_end", "reasoning_delta", "reasoning_end" -> stream(event, raw, chatId)

            "message_accepted" -> MessageAcceptedFrame(
                turnId = raw.text("turn_id"),
                startsTurn = raw.flag("starts_turn"),
                activeTurnId = raw.text("active_turn_id"),
                startedAt = raw.decimal("started_at"),
                chatId = chatId,
                raw = raw,
            )

            "session_updated" -> SessionUpdatedFrame(scope = raw.text("scope"), chatId = chatId, raw = raw)
            "turn_model_updated" -> TurnModelUpdatedFrame(
                modelName = raw.text("model_name"),
                modelPreset = raw.text("model_preset"),
                contextWindowTokens = raw.integer("context_window_tokens"),
                fallback = raw.flag("fallback"),
                reauthProvider = raw.text("reauth_provider"),
                chatId = chatId,
                raw = raw,
            )

            "goal_status" -> GoalStatusFrame(
                status = raw.text("status"),
                startedAt = raw.decimal("started_at"),
                chatId = chatId,
                raw = raw,
            )

            "goal_state" -> GoalStateFrame(goalState = raw.child("goal_state"), chatId = chatId, raw = raw)
            "retry_status" -> RetryStatusFrame(
                state = raw.text("state"),
                attempt = raw.integer("attempt"),
                maxAttempts = raw.integer("max_attempts"),
                errorKind = raw.text("error_kind"),
                retryAfterSeconds = raw.decimal("retry_after_s"),
                chatId = chatId,
                raw = raw,
            )

            "recovery_state" -> RecoveryStateFrame(
                status = raw.text("status"),
                recoveryId = raw.text("recovery_id"),
                attempts = raw.integer("attempts"),
                reason = raw.text("reason"),
                canContinue = raw.flag("can_continue"),
                failureErrorKind = raw.text("failure_error_kind"),
                replyPreserved = raw.flag("reply_preserved"),
                chatId = chatId,
                raw = raw,
            )

            "turn_end" -> TurnEndFrame(
                turnId = raw.text("turn_id"),
                latencyMs = raw.long("latency_ms"),
                usage = raw.child("usage")?.let(Usage::of),
                roundUsages = raw.items("round_usages").map(Usage::of),
                contextWindowTokens = raw.integer("context_window_tokens"),
                outcome = raw.text("outcome"),
                failureKind = raw.text("failure_kind"),
                failureErrorKind = raw.text("failure_error_kind"),
                failureAttempts = raw.integer("failure_attempts"),
                failureMessage = raw.text("failure_message"),
                goalState = GoalSummary.of(raw.child("goal_state")),
                turnPhase = raw.text("turn_phase"),
                turnSeq = raw.integer("turn_seq"),
                chatId = chatId,
                raw = raw,
            )

            "message" -> MessageFrame(
                text = raw.text("text"),
                renderAs = raw.text("render_as"),
                media = raw.texts("media"),
                mediaUrls = raw.items("media_urls"),
                replyTo = raw.text("reply_to"),
                latencyMs = raw.long("latency_ms"),
                toolEvents = raw.items("tool_events"),
                kind = raw.text("kind"),
                turnId = raw.text("turn_id"),
                chatId = chatId,
                raw = raw,
            )

            "user_message" -> UserMessageFrame(
                text = raw.text("text"),
                createdAtMs = raw.long("created_at_ms"),
                startsTurn = raw.flag("starts_turn"),
                inputId = raw.text("input_id"),
                provenance = raw.child("provenance"),
                turnId = raw.text("turn_id"),
                turnPhase = raw.text("turn_phase"),
                turnSeq = raw.integer("turn_seq"),
                chatId = chatId,
                raw = raw,
            )

            "file_edit" -> FileEditFrame(
                edits = raw.items("edits"),
                turnId = raw.text("turn_id"),
                turnPhase = raw.text("turn_phase"),
                turnSeq = raw.integer("turn_seq"),
                chatId = chatId,
                raw = raw,
            )

            "model_request" -> ModelRequestFrame(
                requestId = raw.text("request_id"),
                runId = raw.text("run_id"),
                sequence = raw.integer("sequence"),
                phase = raw.text("phase"),
                requestUsage = raw.child("request_usage")?.let(Usage::of),
                usage = raw.child("usage")?.let(Usage::of),
                roundUsages = raw.items("round_usages").map(Usage::of),
                contextWindowTokens = raw.integer("context_window_tokens"),
                turnId = raw.text("turn_id"),
                chatId = chatId,
                raw = raw,
            )

            "context_compaction" -> ContextCompactionFrame(
                compactionId = raw.text("compaction_id"),
                phase = raw.text("phase"),
                reason = raw.text("reason"),
                turnId = raw.text("turn_id"),
                chatId = chatId,
                raw = raw,
            )

            "subagent_task" -> SubagentTaskFrame(
                task = raw.child("task"),
                turnId = raw.text("turn_id"),
                chatId = chatId,
                raw = raw,
            )

            else -> unknown(event ?: "", chatId, raw)
        }
    }

    /**
     * Re-encoding is the frame's own payload: the codec never rebuilds it from the typed
     * fields, so nothing a newer gateway sent is silently dropped on the way back out.
     */
    fun encode(frame: Frame): String = frame.raw.toString()

    private fun stream(event: String, raw: JsonObject, chatId: String?): Frame {
        val sources = raw.items("response_sources").map { source ->
            ResponseSource(
                provider = source.text("provider"),
                model = source.text("model"),
                preset = source.text("preset"),
                fallback = source.flag("fallback"),
            )
        }
        val inputIds = raw.texts("response_input_ids")
        val text = raw.text("text")
        val streamId = raw.text("stream_id")
        val turnId = raw.text("turn_id")
        val phase = raw.text("turn_phase")
        val seq = raw.integer("turn_seq")
        val resuming = raw.flag("resuming")
        val mergeNext = raw.flag("merge_next")
        return when (event) {
            "delta" -> DeltaFrame(text, streamId, turnId, phase, seq, resuming, mergeNext, sources, inputIds, chatId, raw)
            "stream_end" -> StreamEndFrame(
                text, streamId, turnId, phase, seq, raw.integer("text_from_seq"),
                resuming, mergeNext, sources, inputIds, chatId, raw,
            )
            "reasoning_delta" -> ReasoningDeltaFrame(text, streamId, turnId, phase, seq, resuming, mergeNext, sources, inputIds, chatId, raw)
            else -> ReasoningEndFrame(text, streamId, turnId, phase, seq, resuming, mergeNext, sources, inputIds, chatId, raw)
        }
    }

    private fun unknown(event: String, chatId: String?, raw: JsonObject): UnknownFrame =
        UnknownFrame(event = event, chatId = chatId, raw = raw)
}
