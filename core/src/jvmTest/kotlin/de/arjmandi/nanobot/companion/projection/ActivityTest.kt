package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.fixture.Fixtures
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Activity rows (plan.md C4.4). The gateway reports tool work as `message.tool_events`
 * snapshots — one frame when a call starts, another when it ends — and file work as
 * `file_edit` with a unified diff. A row is keyed by the call id, so the second frame
 * settles the first row instead of adding a second one.
 *
 * The tool and file scenarios are the recorded evidence (P3); the failure case is a
 * constructed frame because a failing tool call cannot be provoked reliably on the dev
 * gateway, and the shape is the same one the recorded frames carry plus `error`.
 */
class ActivityTest {

    private fun reduce(frames: List<Frame>): ThreadState =
        frames.fold(ThreadState(chatId = "chat-1")) { state, frame ->
            ThreadReducer.reduce(state, frame, nowMs = 1_000).state
        }

    private fun recorded(scenario: String): List<Frame> =
        Fixtures.scenarioFrames(scenario).map { FrameCodec.decode(it.toString()) }

    private fun tools(state: ThreadState): List<ActivityItem.Tool> =
        state.turns.flatMap { it.activity }.filterIsInstance<ActivityItem.Tool>()

    @Test
    fun toolCallRunningThenDone() {
        val frames = recorded("tool")
        val startIndex = frames.indexOfFirst { it is de.arjmandi.nanobot.companion.wire.MessageFrame }
        assertTrue(startIndex > 0, "the tool scenario recorded no message frame; run :core-cli record")

        val running = tools(reduce(frames.take(startIndex + 1)))
        assertEquals(1, running.size, "one call is one row")
        val started = running.single()
        assertEquals(ToolStatus.Running, started.status, "a started call is a running row, not a finished one")
        assertEquals("exec", started.name, "the row is labelled with the tool the model called")
        assertTrue(!started.summary.isNullOrBlank(), "the row shows what was asked of the tool")

        val settled = tools(reduce(frames))
        assertEquals(1, settled.size, "the end frame settles the row it started instead of adding another")
        val done = settled.single()
        assertEquals(started.id, done.id, "the row keeps its id across the two frames")
        assertEquals(ToolStatus.Done, done.status)
        assertTrue(
            done.detail.orEmpty().contains("nanobot-tool-probe"),
            "the finished row carries the tool's result; got ${done.detail}",
        )
    }

    @Test
    fun toolFailureMarksRowFailed() {
        val failed = FrameCodec.decode(
            """
            {"event":"message","chat_id":"chat-1","turn_id":"turn-1","text":"","kind":"progress",
             "tool_events":[{"version":1,"phase":"end","call_id":"call-1","name":"exec",
                             "arguments":{"command":"exit 2"},"result":null,
                             "error":"command exited 2","files":[],"embeds":[]}]}
            """.trimIndent(),
        )

        val row = tools(reduce(listOf(failed))).single()

        assertEquals(ToolStatus.Failed, row.status, "a call that ended with an error is a failed row")
        assertTrue(row.detail.orEmpty().contains("exited 2"), "the row explains the failure; got ${row.detail}")
    }

    @Test
    fun fileEditBecomesDiffRows() {
        val frames = recorded("file")
        val edits = frames.filterIsInstance<de.arjmandi.nanobot.companion.wire.FileEditFrame>()
        assertTrue(edits.isNotEmpty(), "the file scenario recorded no file_edit; run :core-cli record")

        val rows = reduce(frames).turns.flatMap { it.activity }.filterIsInstance<ActivityItem.FileEdit>()

        assertEquals(1, rows.size, "one edit of one file is one row, even though it arrived twice")
        val row = rows.single()
        assertEquals("tool-probe.txt", row.path, "the row shows the path the tool worked on")
        assertEquals(ToolStatus.Done, row.status)
        assertEquals(1, row.added, "the finished edit reports what it changed")
        assertTrue(
            row.diff.orEmpty().contains("+nanobot"),
            "the diff is what the row renders; got ${row.diff}",
        )
    }

    @Test
    fun fileEditWhileWritingIsRunning() {
        val frames = recorded("file")
        val first = frames.first { it is de.arjmandi.nanobot.companion.wire.FileEditFrame }
        val index = frames.indexOf(first)

        val row = reduce(frames.take(index + 1)).turns.flatMap { it.activity }.filterIsInstance<ActivityItem.FileEdit>().single()

        assertEquals(ToolStatus.Running, row.status, "the start phase has no diff yet, so the row is still running")
        assertEquals(null, row.diff)
    }
}
