package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.ContextCompactionFrame
import de.arjmandi.nanobot.companion.wire.DeltaFrame
import de.arjmandi.nanobot.companion.wire.ErrorFrame
import de.arjmandi.nanobot.companion.wire.FileEditFrame
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.GoalStateFrame
import de.arjmandi.nanobot.companion.wire.MessageAcceptedFrame
import de.arjmandi.nanobot.companion.wire.MessageFrame
import de.arjmandi.nanobot.companion.wire.ReasoningDeltaFrame
import de.arjmandi.nanobot.companion.wire.RecoveryStateFrame
import de.arjmandi.nanobot.companion.wire.RetryStatusFrame
import de.arjmandi.nanobot.companion.wire.ReasoningEndFrame
import de.arjmandi.nanobot.companion.wire.SessionUpdatedFrame
import de.arjmandi.nanobot.companion.wire.StreamEndFrame
import de.arjmandi.nanobot.companion.wire.TurnEndFrame
import de.arjmandi.nanobot.companion.wire.TurnModelUpdatedFrame
import de.arjmandi.nanobot.companion.wire.UnknownFrame
import de.arjmandi.nanobot.companion.wire.UserMessageFrame
import de.arjmandi.nanobot.companion.wire.child
import de.arjmandi.nanobot.companion.wire.flag
import de.arjmandi.nanobot.companion.wire.integer
import de.arjmandi.nanobot.companion.wire.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One reducer, two feeds (plan.md D9). Live frames and replayed history use the same
 * vocabulary, so this function serves both, and it is pure: time arrives as [nowMs], and
 * anything the engine must do leaves as [Effect] data instead of a call.
 */
internal object ThreadReducer {

    fun reduce(state: ThreadState, frame: Frame, nowMs: Long): Reduction = when (frame) {
        is DeltaFrame -> appendAnswer(state, frame.streamId, frame.text, frame.turnId, nowMs)
        is StreamEndFrame -> closeAnswer(state, frame.streamId, frame.turnId, frame.text)
        is ReasoningDeltaFrame -> appendReasoning(state, frame.streamId, frame.text, frame.turnId)
        is ReasoningEndFrame -> closeReasoning(state, frame.streamId, frame.turnId, frame.text)
        is UserMessageFrame -> addPrompt(state, frame)
        is MessageFrame -> addToolEvents(state, frame)
        is FileEditFrame -> addFileEdits(state, frame)
        is MessageAcceptedFrame -> settleOptimisticPrompt(state, frame)
        is TurnEndFrame -> settleTurn(state, frame)
        is TurnModelUpdatedFrame -> {
            val updated = state.copy(
                modelName = frame.modelName ?: state.modelName,
                modelPreset = frame.modelPreset ?: state.modelPreset,
                contextWindowTokens = frame.contextWindowTokens ?: state.contextWindowTokens,
            )
            // The preset asked for one model and another answered: that is a notice, not a
            // silent chip change (C6.2).
            if (frame.fallback) {
                addNotice(updated, Notice.PresetFallback(id = FALLBACK_NOTICE, modelName = frame.modelName))
            } else {
                updated.copy(notices = updated.notices.filterNot { it.id == FALLBACK_NOTICE })
            }
        }
        is ContextCompactionFrame -> compaction(state, frame)
        is RecoveryStateFrame -> recovery(state, frame)
        is RetryStatusFrame -> retry(state, frame, nowMs)
        is GoalStateFrame -> goalState(state, frame)
        is SessionUpdatedFrame -> state
        is ErrorFrame -> addNotice(state, Notice.Failure(frame.turnIdForNotice(), frame.detail, frame.detail))
        // A frame this client does not model must not disturb a thread it is still showing.
        is UnknownFrame -> state
        else -> state
    }.let { newState ->
        if (newState == state) Reduction(state) else Reduction(newState, listOf(Effect.PersistCache(state.chatId)))
    }

    /**
     * A stream is identified by the gateway's opaque `stream_id` (D15.3). Text always lands
     * on the last open segment for that id; a delta that arrives after the segment closed
     * opens a new one rather than reopening a settled bubble.
     */
    private fun appendAnswer(
        state: ThreadState,
        streamId: String?,
        text: String?,
        turnId: String?,
        nowMs: Long,
    ): ThreadState {
        if (text.isNullOrEmpty()) return ensureTurn(state, turnId, nowMs)
        val id = streamId ?: turnId ?: state.activeTurnId ?: NEW_STREAM
        return updateTurn(state, turnId, nowMs) { turn ->
            val segments = turn.answer.segments
            val last = segments.lastOrNull()
            val updated = if (last != null && last.id == id && !last.closed) {
                segments.dropLast(1) + last.copy(text = last.text + text)
            } else {
                // A later segment for the same stream gets a distinct id so a view keyed by
                // segment id does not collapse two bubbles into one.
                val segmentId = if (segments.any { it.id == id }) "$id#${segments.size}" else id
                segments + StreamSegment(id = segmentId, text = text)
            }
            turn.copy(answer = turn.answer.copy(segments = updated))
        }
    }

    private fun closeAnswer(state: ThreadState, streamId: String?, turnId: String?, text: String?): ThreadState =
        updateTurn(state, turnId, null) { turn ->
            val segments = turn.answer.segments
            val last = segments.lastOrNull()
            val closed = when {
                // A stream_end with no text and no deltas before it is a placeholder: the live
                // socket sends one per answer phase and the history page does not store it, so
                // keeping it would give the turn an empty bubble (found by `watch --verify`, D37).
                last == null -> if (text.isNullOrEmpty()) {
                    segments
                } else {
                    listOf(StreamSegment(id = streamId ?: NEW_STREAM, text = text, closed = true))
                }
                last.id == streamId || streamId == null -> segments.dropLast(1) + last.copy(closed = true)
                // The recorded history puts the whole text on stream_end when no delta preceded
                // it. An *empty* stream_end for a stream that carried nothing is a placeholder
                // the live socket emits and the history page does not store, and rendering it
                // gives the turn an empty bubble (found by `core-cli watch --verify`, D37).
                !text.isNullOrEmpty() -> segments + StreamSegment(id = streamId, text = text, closed = true)
                else -> segments
            }
            turn.copy(answer = turn.answer.copy(segments = closed))
        }

    private fun appendReasoning(state: ThreadState, streamId: String?, text: String?, turnId: String?): ThreadState {
        if (text.isNullOrEmpty()) return state
        val id = streamId ?: REASONING_STREAM
        return updateTurn(state, turnId, null) { turn ->
            val reasoning = turn.answer.reasoning
            val last = reasoning.lastOrNull()
            val updated = if (last != null && last.id == id && !last.closed) {
                reasoning.dropLast(1) + last.copy(text = last.text + text)
            } else {
                reasoning + StreamSegment(id = id, text = text)
            }
            turn.copy(answer = turn.answer.copy(reasoning = updated))
        }
    }

    /**
     * The socket streams reasoning in deltas and ends the stream with a text-less
     * `reasoning_end`; the history page stores one `reasoning_end` per block carrying the block's
     * whole text (recorded: `fixtures/…/file/history.json`). Both feeds therefore have to land a
     * bubble, and neither may drop the text the other form carries (C4.3, D35).
     */
    private fun closeReasoning(state: ThreadState, streamId: String?, turnId: String?, text: String?): ThreadState =
        updateTurn(state, turnId, null) { turn ->
            val id = streamId ?: REASONING_STREAM
            val reasoning = turn.answer.reasoning
            val last = reasoning.lastOrNull()
            val closed = when {
                last != null && last.id == id && !last.closed -> reasoning.dropLast(1) + last.copy(closed = true)
                !text.isNullOrEmpty() -> {
                    // A block that only exists as this frame; a later block for the same stream
                    // id gets its own segment so a view keyed by segment id does not merge them.
                    val segmentId = if (reasoning.any { it.id == id }) "$id#${reasoning.size}" else id
                    reasoning + StreamSegment(id = segmentId, text = text, closed = true)
                }
                else -> reasoning.map { if (it.id == id) it.copy(closed = true) else it }
            }
            turn.copy(answer = turn.answer.copy(reasoning = closed, reasoningDone = true))
        }

    /**
     * A tool call arrives as two snapshots of the same call: `phase: start` while it runs
     * and `phase: end` when it settles, joined by `call_id`. The row is upserted by that id
     * in the turn's activity list, so a view keyed by row id sees one row change state
     * instead of two rows appear.
     */
    private fun addToolEvents(state: ThreadState, frame: MessageFrame): ThreadState {
        if (frame.toolEvents.isEmpty()) return state
        val items = frame.toolEvents.mapIndexed { index, event ->
            val name = event.text("name") ?: TOOL_UNKNOWN
            val error = event.text("error")
            ActivityItem.Tool(
                // One gateway call reaches the client as both a tool event and, when it
                // touches a file, a file_edit. The families are keyed apart so the two rows
                // do not overwrite each other, and a file edit then takes over the call.
                id = "tool:" + (event.text("call_id") ?: "${frame.turnId ?: NEW_STREAM}-tool-$index"),
                name = name,
                summary = toolSummary(event.child("arguments")),
                status = activityStatus(event.text("phase"), event.text("status"), error),
                detail = error ?: event.text("result"),
            )
        }
        return sandbox(upsertActivity(state, frame.turnId, items), frame)
    }

    /**
     * Shell tools are the one activity that produces a standing warning: a failed `exec`
     * with a sandbox `error_code` pauses every shell tool, so it is a notice rather than a
     * row (`useSandboxNotice.ts`). A later successful `exec` retires it.
     */
    private fun sandbox(state: ThreadState, frame: MessageFrame): ThreadState {
        var updated = state
        for (event in frame.toolEvents) {
            if (event.text("name") != EXEC_TOOL) continue
            val phase = event.text("phase")
            if (phase == "end") {
                updated = updated.copy(notices = updated.notices.filterNot { it is Notice.Sandbox })
                continue
            }
            val issue = sandboxIssues[event.text("error_code")] ?: continue
            updated = addNotice(updated, Notice.Sandbox(id = SANDBOX_NOTICE, issue = issue))
        }
        return updated
    }

    /** `file_edit` carries one diff per edited file; the finished edit is the one C4.4 renders. */
    private fun addFileEdits(state: ThreadState, frame: FileEditFrame): ThreadState {
        if (frame.edits.isEmpty()) return state
        val items = frame.edits.mapIndexed { index, edit ->
            ActivityItem.FileEdit(
                id = "file:" + (edit.text("call_id") ?: edit.text("path") ?: "${frame.turnId ?: NEW_STREAM}-edit-$index"),
                path = edit.text("path") ?: edit.text("absolute_path").orEmpty(),
                status = activityStatus(edit.text("phase"), edit.text("status"), edit.text("error")),
                added = edit.integer("added") ?: 0,
                deleted = edit.integer("deleted") ?: 0,
                operation = edit.text("operation"),
                diff = edit.child("diff")?.text("text"),
            )
        }
        return upsertActivity(state, frame.turnId, items)
    }

    /**
     * `phase` is the lifecycle word the gateway uses for both families; `status` is the
     * tool's own, and an error overrides both. Keeping the rule in one function means a
     * new tool family cannot invent a third meaning for `running`.
     */
    private fun activityStatus(phase: String?, status: String?, error: String?): ToolStatus = when {
        error != null || status == "failed" || status == "error" -> ToolStatus.Failed
        phase == "start" || status == "editing" || status == "running" -> ToolStatus.Running
        else -> ToolStatus.Done
    }

    /** The one line an activity row shows while it runs: what was asked of the tool. */
    private fun toolSummary(arguments: JsonObject?): String? {
        if (arguments == null) return null
        val value = SUMMARY_KEYS.firstNotNullOfOrNull { arguments.text(it) }
            ?: arguments.values.filterIsInstance<JsonPrimitive>().firstOrNull { it.isString }?.content
        return value?.let { if (it.length > SUMMARY_LIMIT) it.take(SUMMARY_LIMIT) + "…" else it }
    }

    /**
     * Replace a row in place when its id is already there; append it otherwise. A file edit
     * also retires the tool row for its own call: the design shows one row per edited file,
     * not a tool row next to it, and both describe the same call.
     */
    private fun upsertActivity(state: ThreadState, turnId: String?, items: List<ActivityItem>): ThreadState =
        updateTurn(state, turnId, 0L) { turn ->
            val merged = turn.activity.toMutableList()
            for (item in items) {
                if (item is ActivityItem.FileEdit) {
                    val callId = item.id.removePrefix("file:")
                    merged.removeAll { it is ActivityItem.Tool && it.id == "tool:$callId" }
                }
                val existing = merged.indexOfFirst { it.id == item.id }
                if (existing >= 0) merged[existing] = item else merged += item
            }
            turn.copy(activity = merged.toList())
        }

    /**
     * The bubble the composer puts up before the gateway has answered (C6.1). It is a turn the
     * client named, so `message_accepted` can find it and `turn_end` can settle it.
     */
    fun addLocalPrompt(
        state: ThreadState,
        turnId: String,
        text: String,
        quote: String?,
        nowMs: Long,
    ): ThreadState = state.copy(
        turns = state.turns + Turn(
            id = turnId,
            prompt = UserPrompt(text = text, quote = quote, optimistic = true, createdAtMs = nowMs),
            startedAtMs = nowMs,
        ),
        activeTurnId = turnId,
    )

    private fun addPrompt(state: ThreadState, frame: UserMessageFrame): ThreadState {
        // `user_message` starts a turn just as `message_accepted` does, and both are clean
        // slates for the sandbox warning (useSandboxNotice.ts:29).
        val opened = if (frame.startsTurn) state.copy(notices = state.notices.filterNot { it is Notice.Sandbox }) else state
        val turnId = frame.turnId ?: opened.activeTurnId ?: return opened
        val existing = opened.turn(turnId)
        // The gateway uses a quote as model-only context and never echoes it (C4.9), so the
        // one this client rendered has to survive the frame that carries the prompt.
        val prompt = UserPrompt(
            text = frame.text.orEmpty(),
            quote = existing?.prompt?.quote,
            optimistic = false,
            createdAtMs = frame.createdAtMs,
        )
        return if (existing == null) {
            opened.copy(
                turns = opened.turns + Turn(id = turnId, prompt = prompt, startedAtMs = frame.createdAtMs),
                activeTurnId = opened.activeTurnId ?: turnId,
            )
        } else {
            opened.copy(turns = opened.turns.map { if (it.id == turnId) it.copy(prompt = prompt) else it })
        }
    }

    /** `message_accepted` is the gateway's ack: the optimistic bubble stops being optimistic (C4.5). */
    private fun settleOptimisticPrompt(state: ThreadState, frame: MessageAcceptedFrame): ThreadState {
        // A turn that starts is a clean slate for the standing warning (useSandboxNotice.ts:29).
        val clean = if (frame.startsTurn) state.copy(notices = state.notices.filterNot { it is Notice.Sandbox }) else state
        val turnId = frame.turnId ?: frame.activeTurnId ?: return clean
        val existing = clean.turn(turnId)
        return if (existing == null) {
            clean.copy(activeTurnId = turnId)
        } else {
            clean.copy(
                activeTurnId = frame.activeTurnId ?: turnId,
                turns = clean.turns.map { turn ->
                    if (turn.id == turnId && turn.prompt != null) {
                        turn.copy(prompt = turn.prompt.copy(optimistic = false))
                    } else {
                        turn
                    }
                },
            )
        }
    }

    /** One row per compaction id, and its phase is what changes (C7.3). */
    private fun compaction(state: ThreadState, frame: ContextCompactionFrame): ThreadState {
        val phase = frame.phase ?: return state
        val id = frame.compactionId ?: COMPACTION_NOTICE
        return addNotice(state, Notice.Compaction(id = id, phase = phase, reason = frame.reason))
    }

    /**
     * Recovery is a decision surface, so it exists only while a decision is outstanding:
     * `awaiting_user` and `failed` ask something, `resuming` and `recovered` do not.
     */
    private fun recovery(state: ThreadState, frame: RecoveryStateFrame): ThreadState {
        val waiting = frame.status == "awaiting_user" || frame.status == "failed"
        // One recovery state per chat, as the stream hook keeps it: a new id replaces the
        // previous decision rather than stacking two prompts to continue.
        val cleared = state.copy(notices = state.notices.filterNot { it is Notice.Recovery })
        if (!waiting) return cleared
        return addNotice(
            cleared,
            Notice.Recovery(
                id = frame.recoveryId ?: RECOVERY_NOTICE,
                status = frame.status.orEmpty(),
                canContinue = frame.canContinue,
                reason = frame.reason,
                failureErrorKind = frame.failureErrorKind,
                replyPreserved = frame.replyPreserved,
            ),
        )
    }

    /** The countdown is the frame's delay on the injected clock; nothing here holds a timer. */
    private fun retry(state: ThreadState, frame: RetryStatusFrame, nowMs: Long): ThreadState {
        if (frame.state != "waiting") return state.copy(notices = state.notices.filterNot { it is Notice.Retry })
        val delayMs = frame.retryAfterSeconds?.let { (it * 1_000).toLong() }
        return addNotice(
            state,
            Notice.Retry(
                id = RETRY_NOTICE,
                attempt = frame.attempt ?: 0,
                maxAttempts = frame.maxAttempts,
                errorKind = frame.errorKind,
                retryAtMs = delayMs?.let { nowMs + it },
            ),
        )
    }

    /** The strip shows `ui_summary`, else the objective; `active: false` takes it away. */
    private fun goalState(state: ThreadState, frame: GoalStateFrame): ThreadState {
        val goal = frame.goalState
        val active = goal?.flag("active") ?: false
        if (!active) return state.copy(notices = state.notices.filterNot { it is Notice.Goal })
        val summary = goal.text("ui_summary")?.takeIf { it.isNotBlank() }
            ?: goal.text("objective")?.takeIf { it.isNotBlank() }
        return addNotice(
            state,
            Notice.Goal(id = GOAL_NOTICE, active = true, status = goal.text("status"), summary = summary),
        )
    }

    /** The sheet's own dismiss: the notice is client state, so this is a pure reduction. */
    fun dismiss(state: ThreadState, noticeId: String): ThreadState =
        state.copy(notices = state.notices.filterNot { it.id == noticeId })

    private fun settleTurn(state: ThreadState, frame: TurnEndFrame): ThreadState {
        val turnId = frame.turnId ?: state.activeTurnId ?: return state
        val failed = frame.failureKind != null || frame.outcome == "failed"
        val settled = updateTurn(state, turnId, null) { turn ->
            turn.copy(
                status = if (failed) TurnStatus.Failed else TurnStatus.Complete,
                // A settle frame with no usage says nothing about the ring; keep what we had.
                usage = frame.usage ?: turn.usage,
                failureKind = frame.failureKind,
            )
        }
        return settled.copy(
            activeTurnId = if (settled.activeTurnId == turnId) null else settled.activeTurnId,
            usage = frame.usage ?: settled.usage,
            contextWindowTokens = frame.contextWindowTokens ?: settled.contextWindowTokens,
            // The strip is drawn only while a goal is active, so a settle that says
            // `active: false` takes it away rather than drawing an empty strip (C7.3).
            notices = settled.notices.filterNot { it is Notice.Goal } +
                listOfNotNull(
                    Notice.Goal(id = GOAL_NOTICE, active = true).takeIf { frame.goalState.active },
                ),
        )
    }

    private fun addNotice(state: ThreadState, notice: Notice): ThreadState =
        state.copy(notices = state.notices.filterNot { it.id == notice.id } + notice)

    private fun ensureTurn(state: ThreadState, turnId: String?, nowMs: Long): ThreadState {
        val id = turnId ?: state.activeTurnId ?: return state
        return if (state.turn(id) == null) {
            state.copy(turns = state.turns + Turn(id = id, startedAtMs = nowMs), activeTurnId = id)
        } else {
            state
        }
    }

    private fun updateTurn(
        state: ThreadState,
        turnId: String?,
        nowMs: Long?,
        change: (Turn) -> Turn,
    ): ThreadState {
        val id = turnId ?: state.activeTurnId ?: return if (nowMs == null) state else ensureTurn(state, turnId, nowMs)
        val prepared = if (state.turn(id) == null) ensureTurn(state, id, nowMs ?: 0) else state
        return prepared.copy(
            turns = prepared.turns.map { if (it.id == id) change(it) else it },
            activeTurnId = prepared.activeTurnId ?: id,
        )
    }

    private fun ErrorFrame.turnIdForNotice(): String = "error-$detail"

    private const val NEW_STREAM = "stream-unknown"
    private const val REASONING_STREAM = "reasoning"
    private const val GOAL_NOTICE = "goal"
    private const val COMPACTION_NOTICE = "compaction"
    private const val RECOVERY_NOTICE = "recovery"
    private const val RETRY_NOTICE = "retry"
    private const val SANDBOX_NOTICE = "sandbox"
    private const val EXEC_TOOL = "exec"
    private const val FALLBACK_NOTICE = "preset-fallback"
    private const val TOOL_UNKNOWN = "tool"
    private const val SUMMARY_LIMIT = 80

    /** The seven codes the WebUI maps to four sentences (`useSandboxNotice.ts:5-14`). */
    private val sandboxIssues: Map<String, SandboxIssue> = mapOf(
        "sandbox_launcher_missing" to SandboxIssue.LauncherMissing,
        "sandbox_path_not_absolute" to SandboxIssue.Path,
        "sandbox_not_executable" to SandboxIssue.Path,
        "sandbox_path_unavailable" to SandboxIssue.Path,
        "sandbox_agent_writable" to SandboxIssue.Permissions,
        "sandbox_unprotected_installation" to SandboxIssue.Permissions,
        "sandbox_start_failed" to SandboxIssue.Startup,
    )

    /** The argument a human would name the call by, in the order the tools use them. */
    private val SUMMARY_KEYS = listOf("command", "path", "query", "url", "pattern", "prompt")
}
