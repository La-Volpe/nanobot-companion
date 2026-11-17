package de.arjmandi.nanobot.companion.activity

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.arjmandi.nanobot.companion.chat.ChatTurnView

/**
 * The activity cluster per turn: what nanobot thought, ran and changed, folded into one
 * row that opens on a tap. The twin of iOS's `ActivityViewModel`, and the same two rules live here
 * rather than in a composable: a running turn shows its work and a finished turn folds it, a
 * reader's tap wins over both, and a failure overrides the fold, because a turn that went wrong
 * should not hide the reason it did.
 *
 * [now] is injected so "Thought for 8s" is a computed value a test can pin, never a clock read
 * inside the reducer.
 */
class ActivityViewModel(private val now: () -> Long = { System.currentTimeMillis() }) {

    var clusters: Map<String, ActivityCluster> by mutableStateOf(emptyMap())
        private set

    /** The thread's turns that have something to show, in thread order. */
    var turnIds: List<String> by mutableStateOf(emptyList())
        private set

    /** Turns the reader deliberately opened or closed; theirs, until the turn leaves the thread. */
    private val overrides = mutableMapOf<String, Boolean>()

    /**
     * Rows whose details the reader opened, by row id. A row starts folded, one line, like the
     * web's `ActivityStep`, and a tap is what shows the arguments and the output.
     */
    private val openRows = mutableSetOf<String>()

    fun cluster(turnId: String): ActivityCluster? = clusters[turnId]

    fun update(turns: List<ChatTurnView>) {
        val clock = now()
        val next = turns.filter { it.activity.isNotEmpty() }
            .associate { it.id to cluster(it, clock) }
        clusters = next
        turnIds = turns.map { it.id }.filter { next[it] != null }
        // An override only lasts as long as the turn it was made on: once the turn is out of the
        // thread there is nothing left to remember.
        overrides.keys.retainAll(next.keys)
        openRows.retainAll(next.values.flatMap { cluster -> cluster.items.map { it.id } }.toSet())
    }

    fun toggle(turnId: String) {
        val cluster = clusters[turnId] ?: return
        overrides[turnId] = !cluster.isOpen
        clusters = clusters + (turnId to cluster.opening(!cluster.isOpen))
    }

    fun toggleDetails(itemId: String) {
        if (!openRows.remove(itemId)) openRows += itemId
        clusters = clusters.mapValues { (_, cluster) ->
            if (cluster.items.none { it.id == itemId }) {
                cluster
            } else {
                cluster.copy(items = cluster.items.map { if (it.id == itemId) it.copy(detailsOpen = itemId in openRows) else it })
            }
        }
    }

    private fun cluster(turn: ChatTurnView, clock: Long): ActivityCluster {
        val steps = turn.activity.count {
            it.kind is ActivityKind.Tool || it.kind is ActivityKind.FileEdit
        }
        val files = turn.activity.count { it.kind is ActivityKind.FileEdit }
        val failed = turn.activity.any {
            when (val kind = it.kind) {
                is ActivityKind.Tool -> kind.status == ToolRunStatus.Failed
                is ActivityKind.FileEdit -> kind.status == ToolRunStatus.Failed
                is ActivityKind.Reasoning, is ActivityKind.Search -> false
            }
        }
        val status = when {
            turn.running -> ActivityStatus.Running
            failed -> ActivityStatus.Failed
            else -> ActivityStatus.Done
        }
        val headline = if (turn.running) {
            ActivityHeadline.Thinking
        } else {
            ActivityHeadline.Finished(
                seconds = secondsFor(turn.startedAtMs, clock),
                steps = steps,
                files = files,
            )
        }
        // A reader who has said what they want wins; otherwise run open, fold finished, and
        // never fold a failure they have not seen.
        return ActivityCluster(
            turnId = turn.id,
            status = status,
            headline = headline,
            items = turn.activity.map { it.copy(detailsOpen = it.id in openRows) },
            isOpen = overrides[turn.id] ?: (turn.running || failed),
            hasFailure = failed,
            steps = steps,
        )
    }

    /** Whole seconds, never "0s": a turn that took less than a second still took a moment. */
    private fun secondsFor(startedAtMs: Long?, clock: Long): Int {
        val elapsed = (clock - (startedAtMs ?: clock)).coerceAtLeast(0)
        return (elapsed / 1000).coerceAtLeast(1).toInt()
    }
}
