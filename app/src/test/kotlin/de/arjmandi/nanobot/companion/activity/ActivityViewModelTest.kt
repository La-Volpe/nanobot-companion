package de.arjmandi.nanobot.companion.activity

import de.arjmandi.nanobot.companion.chat.ChatTurnView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A4's activity cluster: reasoning, tool rows, inline diffs and search sources, grouped by the turn
 * they belong to and folded once that turn is over. The core already reduced the frames
 * (`TurnReducerTest`); this suite owns the drawing rules only — the Android twin of
 * `ActivityViewModelTests` on iOS.
 */
class ActivityViewModelTest {

    private fun turn(
        id: String = "t1",
        running: Boolean = true,
        startedAtMs: Long? = null,
        activity: List<ActivityItemView> = emptyList(),
    ) = ChatTurnView(
        id = id,
        prompt = "q",
        promptPending = false,
        answer = "",
        answerStreaming = running,
        running = running,
        activity = activity,
        startedAtMs = startedAtMs,
    )

    private fun reasoning(id: String = "r1", done: Boolean = false) = ActivityItemView(
        id = id,
        kind = ActivityKind.Reasoning(text = "check the write path first", done = done),
    )

    private fun tool(
        id: String = "tool:call_1",
        name: String = "run_command",
        summary: String? = "pytest -q",
        status: ToolRunStatus = ToolRunStatus.Running,
        detail: String? = null,
    ) = ActivityItemView(
        id = id,
        kind = ActivityKind.Tool(name = name, summary = summary, status = status, detail = detail),
    )

    private fun edit(path: String = "tool-probe.txt", status: ToolRunStatus = ToolRunStatus.Done) = ActivityItemView(
        id = "file:call_2",
        kind = ActivityKind.FileEdit(
            path = path,
            status = status,
            added = 1,
            deleted = 0,
            operation = "create",
            diff = "--- tool-probe.txt\n+++ tool-probe.txt\n@@ -0,0 +1 @@\n+nanobot",
        ),
    )

    /** The plan's red line: one cluster per turn, and a turn that has finished folds it. */
    @Test
    fun groupsByTurnAndCollapses() {
        val model = ActivityViewModel(now = { 7_000 })

        model.update(
            listOf(
                turn(id = "t1", running = true, activity = listOf(reasoning(), tool())),
                turn(id = "t2", running = false, activity = listOf(reasoning("r2", done = true), tool("tool:call_9", status = ToolRunStatus.Done))),
            ),
        )

        assertEquals(listOf("t1", "t2"), model.turnIds, "one cluster per turn, in thread order")
        assertEquals(true, model.cluster("t1")?.isOpen, "a running turn shows what it is doing")
        assertEquals(false, model.cluster("t2")?.isOpen, "a finished turn folds its activity away")
        assertEquals(2, model.cluster("t1")?.items?.size)
        assertNull(model.cluster("missing"), "a turn with no activity has no cluster at all")
    }

    @Test
    fun aTurnWithNoActivityHasNoCluster() {
        val model = ActivityViewModel(now = { 0 })
        model.update(listOf(turn(activity = emptyList())))
        assertTrue(model.turnIds.isEmpty())
    }

    /**
     * The plan's named red line. A tool that failed carries the gateway's own reason (`detail`),
     * and a failure the reader has not seen must not be folded away — the iOS rule, kept here.
     */
    @Test
    fun failedRowShowsRetryReason() {
        val model = ActivityViewModel(now = { 0 })
        model.update(
            listOf(turn(running = false, activity = listOf(tool(status = ToolRunStatus.Failed, detail = "exit 4")))),
        )

        val cluster = model.cluster("t1")
        assertEquals(true, cluster?.isOpen, "a failure the reader has not seen must not be folded away")
        assertEquals(true, cluster?.hasFailure)
        assertEquals(ActivityStatus.Failed, cluster?.status)
        val kind = cluster?.items?.single()?.kind as? ActivityKind.Tool
        assertEquals("exit 4", kind?.detail, "the row says why it failed, not just that it did")
        assertEquals(ToolRunStatus.Failed, kind?.status)
    }

    /** The design's own headline: `Thought for 8s · 4 steps · 2 files changed`, without a zero clause. */
    @Test
    fun theHeadlineCountsStepsAndFiles() {
        // The clock is the injected one; the turn started eight seconds before it.
        val model = ActivityViewModel(now = { 15_000 })
        model.update(
            listOf(
                turn(
                    running = false,
                    startedAtMs = 7_000,
                    activity = listOf(
                        reasoning(done = true),
                        tool("tool:1", status = ToolRunStatus.Done),
                        tool("tool:2", status = ToolRunStatus.Done),
                        edit(),
                    ),
                ),
            ),
        )

        val cluster = model.cluster("t1")
        assertEquals(ActivityStatus.Done, cluster?.status)
        assertEquals(ActivityHeadline.Finished(seconds = 8, steps = 3, files = 1), cluster?.headline)

        model.update(
            listOf(
                turn(
                    running = false,
                    startedAtMs = 7_000,
                    activity = listOf(tool("tool:1", status = ToolRunStatus.Done), tool("tool:2", status = ToolRunStatus.Done)),
                ),
            ),
        )
        assertEquals(
            ActivityHeadline.Finished(seconds = 8, steps = 2, files = 0),
            model.cluster("t1")?.headline,
            "no files changed, no clause for it",
        )

        model.update(listOf(turn(running = true, activity = listOf(tool()))))
        assertEquals(ActivityHeadline.Thinking, model.cluster("t1")?.headline, "a running turn says what it is doing, not how long")
    }

    @Test
    fun aFileEditShowsItsDiffLinesWithCounts() {
        val model = ActivityViewModel(now = { 0 })
        model.update(listOf(turn(activity = listOf(edit()))))

        val state = model.cluster("t1")?.items?.first()?.kind as? ActivityKind.FileEdit
        assertEquals(1, state?.added)
        assertEquals(0, state?.deleted)
        assertEquals(listOf(DiffKind.Hunk, DiffKind.Added), state?.lines?.map { it.kind }, "the ---/+++ headers are not rows")
        assertEquals(listOf("@@ -0,0 +1 @@", "+nanobot"), state?.lines?.map { it.text }, "a diff line keeps its marker")
        assertEquals("nanobot", state?.lines?.last()?.body, "and the body is what the row draws")
    }

    @Test
    fun searchSourcesBecomeChips() {
        val model = ActivityViewModel(now = { 0 })
        val search = ActivityItemView(
            id = "search:1",
            kind = ActivityKind.Search(
                query = "sqlite wal batching",
                sources = listOf("https://sqlite.org/wal.html", "https://github.com/omnilib/aiosqlite/issues/122"),
            ),
        )
        model.update(listOf(turn(activity = listOf(search))))

        val kind = model.cluster("t1")?.items?.first()?.kind as? ActivityKind.Search
        assertEquals("sqlite wal batching", kind?.query)
        assertEquals(2, kind?.sources?.size)
        assertEquals(
            listOf("sqlite.org · wal.html", "github.com · issues/122"),
            model.cluster("t1")?.sourceChips?.map { it.label },
            "a chip is host · path tail, which is what the design draws",
        )
    }

    @Test
    fun togglingReopensACollapsedCluster() {
        val model = ActivityViewModel(now = { 0 })
        model.update(listOf(turn(running = false, activity = listOf(tool(status = ToolRunStatus.Done)))))
        assertEquals(false, model.cluster("t1")?.isOpen)

        model.toggle("t1")
        assertEquals(true, model.cluster("t1")?.isOpen, "a tap opens what the fold hid")

        model.toggle("t1")
        assertEquals(false, model.cluster("t1")?.isOpen)
    }

    @Test
    fun aFinishedTurnDoesNotOverwriteTheReadersChoice() {
        val model = ActivityViewModel(now = { 0 })
        model.update(listOf(turn(running = true, activity = listOf(tool()))))
        model.toggle("t1")
        assertEquals(false, model.cluster("t1")?.isOpen)

        // The same turn settles: the reader had folded it on purpose, so it stays folded.
        model.update(listOf(turn(running = false, activity = listOf(tool(status = ToolRunStatus.Done)))))
        assertEquals(false, model.cluster("t1")?.isOpen)
    }

    @Test
    fun anOverrideForgetsATurnThatLeftTheThread() {
        val model = ActivityViewModel(now = { 0 })
        model.update(listOf(turn(running = true, activity = listOf(tool()))))
        model.toggle("t1")

        model.update(emptyList())
        model.update(listOf(turn(running = true, activity = listOf(tool()))))

        assertEquals(true, model.cluster("t1")?.isOpen, "a turn out of the thread has nothing left to remember")
    }

    /**
     * T9's first complaint: a row printed its arguments and its output. The web draws one line —
     * glyph, name, one-line summary — and keeps the rest behind the row's disclosure, closed by
     * default (`ActivityStep.tsx:77`, tooltip only on hover).
     */
    @Test
    fun rowDetailsStartClosed() {
        val model = ActivityViewModel(now = { 0 })
        model.update(
            listOf(
                turn(
                    activity = listOf(
                        reasoning(),
                        tool(summary = "pytest -q", detail = "collected 12 items"),
                        edit(),
                    ),
                ),
            ),
        )

        val items = model.cluster("t1")?.items.orEmpty()
        assertEquals(3, items.size)
        assertTrue(items.none { it.detailsOpen }, "every row arrives folded: the row is one line")
    }

    @Test
    fun tappingARowOpensItsDetails() {
        val model = ActivityViewModel(now = { 0 })
        model.update(listOf(turn(activity = listOf(tool()))))

        model.toggleDetails("tool:call_1")
        assertTrue(model.cluster("t1")?.items?.single()?.detailsOpen == true, "a tap opens what the row hid")

        model.toggleDetails("tool:call_1")
        assertTrue(model.cluster("t1")?.items?.single()?.detailsOpen == false)
    }

    /** `compactReasoningPreview` (webui `reasoning-preview.ts:1`): the row is one line of prose. */
    @Test
    fun aReasoningRowIsOneLine() {
        val model = ActivityViewModel(now = { 0 })
        val item = ActivityItemView(
            "r1",
            ActivityKind.Reasoning(text = "## Check the **write path**\n\n   first, see [flush](x.md)", done = false),
        )
        model.update(listOf(turn(activity = listOf(item))))

        assertEquals(
            "Check the write path first, see flush",
            model.cluster("t1")?.items?.single()?.preview,
            "markdown is compacted away and the line is a line, not a document",
        )
        assertEquals(false, model.cluster("t1")?.items?.single()?.detailsOpen, "and it is folded like every other row")
    }

    @Test
    fun aRowsDetailsAreForgottenWithItsTurn() {
        val model = ActivityViewModel(now = { 0 })
        model.update(listOf(turn(activity = listOf(tool()))))
        model.toggleDetails("tool:call_1")

        model.update(emptyList())
        model.update(listOf(turn(activity = listOf(tool()))))

        assertTrue(
            model.cluster("t1")?.items?.single()?.detailsOpen == false,
            "a row out of the thread has nothing left to remember",
        )
    }
}
