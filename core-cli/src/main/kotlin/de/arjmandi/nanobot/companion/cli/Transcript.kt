package de.arjmandi.nanobot.companion.cli

import de.arjmandi.nanobot.companion.projection.ActivityItem
import de.arjmandi.nanobot.companion.projection.Notice
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.ToolStatus
import de.arjmandi.nanobot.companion.projection.Turn
import de.arjmandi.nanobot.companion.projection.TurnStatus

/**
 * One thread as plain lines, so two of them diff line for line.
 *
 * The transcript is the instrument for the parity item, so it renders only what the live socket
 * and the WebUI's history page agree on. Gateway turn ids are not shared (a live turn carries
 * this client's id until the gateway names it), so turns are numbered by position. Everything
 * volatile (ids, wall clocks) is left out; everything a user reads is in, one item per line,
 * newlines escaped.
 */
internal object Transcript {

    /**
     * What both feeds know: the turns and what is attached to them, the notices, the usage.
     * The chat id and the model are *commentary*: the history page carries no
     * `turn_model_updated`, so putting them in the body would fail a comparison for a reason that
     * has nothing to do with the thread (observed on the dev gateway).
     */
    fun render(state: ThreadState): String = buildString {
        state.turns.forEachIndexed { index, turn -> renderTurn(index + 1, turn) }
        state.notices.sortedBy { it.id }.forEach { notice -> appendLine(noticeLine(notice)) }
        state.usage?.let { usage ->
            appendLine("usage | prompt=${usage.promptTokens} completion=${usage.completionTokens} total=${usage.totalTokens}")
        }
    }

    /** `#`-prefixed: harness context a diff is not about. */
    fun header(state: ThreadState): String = buildString {
        appendLine("# chat ${state.chatId}")
        state.modelPreset?.let { preset ->
            appendLine("# model $preset | ${state.modelName ?: "-"} | ${state.contextWindowTokens ?: "-"}")
        }
    }

    private fun StringBuilder.renderTurn(number: Int, turn: Turn) {
        appendLine("turn $number")
        turn.prompt?.let { prompt ->
            val flag = if (prompt.optimistic) " (unsettled)" else ""
            appendLine("  prompt |$flag ${line(prompt.text)}")
            prompt.quote?.let { appendLine("  quote | ${line(it)}") }
        }
        turn.activity.forEach { item -> appendLine("  activity | ${activity(item)}") }
        // One line per turn, not per segment: the socket chunks reasoning into several deltas
        // and the history page stores one block, so segments are the wrong unit to diff.
        val reasoning = turn.answer.reasoning.joinToString("") { it.text }
        if (reasoning.isNotBlank()) appendLine("  reasoning | ${line(reasoning)}")
        turn.answer.segments.forEach { appendLine("  answer | ${line(it.text)}") }
        turn.failureKind?.let { appendLine("  failure | ${line(it)}") }
        appendLine("  status | ${turn.status}")
        if (turn.status != TurnStatus.Running) {
            appendLine("  tokens | prompt=${turn.usage?.promptTokens ?: 0} completion=${turn.usage?.completionTokens ?: 0}")
        }
    }

    private fun activity(item: ActivityItem): String = when (item) {
        is ActivityItem.Tool -> "${item.status.label(item.name)}${item.summary?.let { " | ${line(it)}" }.orEmpty()}"
        is ActivityItem.FileEdit -> "${item.status.label("edit")} | ${item.path} +${item.added} -${item.deleted}"
        is ActivityItem.Reasoning -> "reasoning ${if (item.done) "done" else "running"}"
        is ActivityItem.Search -> "search | ${line(item.query)} -> ${item.sources.size} sources"
    }

    private fun ToolStatus.label(name: String): String = when (this) {
        ToolStatus.Running -> "$name running"
        ToolStatus.Done -> "$name done"
        ToolStatus.Failed -> "$name failed"
    }

    /** The notice's copy key, not its sentence: copy belongs to the platform. */
    private fun noticeLine(notice: Notice): String = when (notice) {
        is Notice.Compaction -> "notice | compaction | ${notice.phase}${notice.reason?.let { " | $it" }.orEmpty()}"
        is Notice.Recovery -> "notice | recovery | ${notice.status} | can_continue=${notice.canContinue}"
        is Notice.Retry -> "notice | retry | attempt ${notice.attempt}/${notice.maxAttempts ?: "-"} | ${notice.errorKind ?: "-"}"
        is Notice.Goal -> "notice | goal | active=${notice.active} | ${line(notice.summary.orEmpty())}"
        is Notice.Sandbox -> "notice | sandbox | ${notice.issue.copyKey}"
        is Notice.Failure -> "notice | failure | ${notice.errorKind ?: "-"} | ${line(notice.message.orEmpty())}"
        is Notice.PresetFallback -> "notice | fallback | ${notice.modelName ?: "-"}"
    }

    /** One item, one line: an answer with paragraphs still diffs as one row. */
    private fun line(text: String): String = text.replace("\r", "").replace("\n", "\\n").trim()
}
