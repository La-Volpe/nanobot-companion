package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The subagent dock (parity row `thread.subagents`).
 *
 * The shape is the gateway's `SubagentStatus.as_dict()` (`nanobot/agent/subagent_status.py:43-70`),
 * which the WebUI sends as `subagent_task {task: {...}}` while the parent still owns the task
 * (`session_projection.py:40-47`). There is no recorded fixture for this frame yet, so these
 * assertions are the gateway's own field names and states, not a guess at them.
 */
class SubagentTest {

    private val chat = "websocket:a"

    private fun state(): ThreadState = ThreadState(chatId = chat)

    private fun reduce(state: ThreadState, json: String): ThreadState =
        ThreadReducer.reduce(state, FrameCodec.decode(json), 1_000).state

    private fun task(
        taskId: String = "sub-1",
        state: String = "running",
        label: String = "audit attachment limits",
        iteration: Int = 2,
        elapsed: Double = 4.5,
    ): String = """
        {"event":"subagent_task","chat_id":"$chat",
         "task":{"task_id":"$taskId","revision":3,"label":"$label",
                 "task_description":"Audit the attachment limits and report the gaps.",
                 "state":"$state","phase":"$state","elapsed_seconds":$elapsed,
                 "iteration":$iteration,"origin_turn_id":"turn-1","partial":false,
                 "tool_events":[{"name":"read_file"}],"result":null,"error":null}}
    """.trimIndent()

    @Test
    fun aSubagentTaskBecomesADockRow() {
        val reduced = reduce(state(), task())

        val dock = reduced.subagents.single()
        assertEquals("sub-1", dock.id, "the gateway's task id, not an arrival index")
        assertEquals("audit attachment limits", dock.label)
        assertEquals("running", dock.state)
        assertEquals(2, dock.iteration, "the step count the dock's progress line shows")
        assertEquals(4.5, dock.elapsedSeconds)
        assertEquals("turn-1", dock.originTurnId)
        assertTrue(dock.isActive, "queued, running and stopping are the active states")
    }

    @Test
    fun theSameTaskUpdatesInPlace() {
        val started = reduce(state(), task(state = "queued", iteration = 0))
        val running = reduce(started, task(state = "running", iteration = 3))

        val dock = running.subagents.single()
        assertEquals(3, dock.iteration, "a revision of one task replaces its row")
        assertEquals("running", dock.state)
    }

    @Test
    fun aFinishedTaskIsNoLongerActive() {
        val running = reduce(state(), task())
        val done = reduce(running, task(state = "done"))

        assertTrue(!done.subagents.single().isActive, "a settled subtask does not stay in the dock")
    }

    @Test
    fun twoTasksAreTwoRows() {
        val first = reduce(state(), task(taskId = "sub-1"))
        val both = reduce(first, task(taskId = "sub-2", label = "write the report"))

        assertEquals(listOf("sub-1", "sub-2"), both.subagents.map { it.id })
    }

    @Test
    fun aTaskWithNoIdIsIgnored() {
        val reduced = reduce(state(), """{"event":"subagent_task","chat_id":"$chat","task":{"state":"running"}}""")

        assertTrue(reduced.subagents.isEmpty(), "a row with no id cannot be updated or cancelled")
    }
}
