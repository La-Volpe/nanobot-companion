package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.DeltaFrame
import de.arjmandi.nanobot.companion.wire.ErrorFrame
import de.arjmandi.nanobot.companion.wire.FileEditFrame
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.MessageAcceptedFrame
import de.arjmandi.nanobot.companion.wire.MessageFrame
import de.arjmandi.nanobot.companion.wire.ReasoningDeltaFrame
import de.arjmandi.nanobot.companion.wire.ReasoningEndFrame
import de.arjmandi.nanobot.companion.wire.SessionUpdatedFrame
import de.arjmandi.nanobot.companion.wire.StreamEndFrame
import de.arjmandi.nanobot.companion.wire.TurnEndFrame
import de.arjmandi.nanobot.companion.wire.TurnModelUpdatedFrame
import de.arjmandi.nanobot.companion.wire.UnknownFrame
import de.arjmandi.nanobot.companion.wire.UserMessageFrame
import de.arjmandi.nanobot.companion.wire.child
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
        is ReasoningEndFrame -> closeReasoning(state, frame.streamId, frame.turnId)
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
                last == null -> listOf(StreamSegment(id = streamId ?: NEW_STREAM, text = text.orEmpty(), closed = true))
                last.id == streamId || streamId == null -> segments.dropLast(1) + last.copy(closed = true)
                // The recorded history puts the whole text on stream_end when no delta preceded it.
                text != null -> segments + StreamSegment(id = streamId, text = text, closed = true)
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

    private fun closeReasoning(state: ThreadState, streamId: String?, turnId: String?): ThreadState =
        updateTurn(state, turnId, null) { turn ->
            val id = streamId ?: REASONING_STREAM
            turn.copy(
                answer = turn.answer.copy(
                    reasoning = turn.answer.reasoning.map { if (it.id == id) it.copy(closed = true) else it },
                    reasoningDone = true,
                ),
            )
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
        return upsertActivity(state, frame.turnId, items)
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
        val turnId = frame.turnId ?: state.activeTurnId ?: return state
        val existing = state.turn(turnId)
        // The gateway uses a quote as model-only context and never echoes it (C4.9), so the
        // one this client rendered has to survive the frame that carries the prompt.
        val prompt = UserPrompt(
            text = frame.text.orEmpty(),
            quote = existing?.prompt?.quote,
            optimistic = false,
            createdAtMs = frame.createdAtMs,
        )
        return if (existing == null) {
            state.copy(
                turns = state.turns + Turn(id = turnId, prompt = prompt, startedAtMs = frame.createdAtMs),
                activeTurnId = state.activeTurnId ?: turnId,
            )
        } else {
            state.copy(turns = state.turns.map { if (it.id == turnId) it.copy(prompt = prompt) else it })
        }
    }

    /** `message_accepted` is the gateway's ack: the optimistic bubble stops being optimistic (C4.5). */
    private fun settleOptimisticPrompt(state: ThreadState, frame: MessageAcceptedFrame): ThreadState {
        val turnId = frame.turnId ?: frame.activeTurnId ?: return state
        val existing = state.turn(turnId)
        return if (existing == null) {
            state.copy(activeTurnId = turnId)
        } else {
            state.copy(
                activeTurnId = frame.activeTurnId ?: turnId,
                turns = state.turns.map { turn ->
                    if (turn.id == turnId && turn.prompt != null) {
                        turn.copy(prompt = turn.prompt.copy(optimistic = false))
                    } else {
                        turn
                    }
                },
            )
        }
    }

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
            notices = settled.notices.filterNot { it is Notice.Goal } +
                Notice.Goal(id = GOAL_NOTICE, active = frame.goalState.active),
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
    private const val FALLBACK_NOTICE = "preset-fallback"
    private const val TOOL_UNKNOWN = "tool"
    private const val SUMMARY_LIMIT = 80

    /** The argument a human would name the call by, in the order the tools use them. */
    private val SUMMARY_KEYS = listOf("command", "path", "query", "url", "pattern", "prompt")
}
