import Foundation
import Testing
@testable import iosApp

/// I4's activity cluster: reasoning, tool rows, inline diffs and search sources, grouped by the
/// turn they belong to and collapsed once that turn is over. The core already reduced the
/// frames (`ActivityTest`, `NoticesTest`); this suite owns the drawing rules only.
@MainActor
struct ActivityViewModelTests {

    private func turn(
        id: String = "t1",
        running: Bool = true,
        startedAtMs: Int64 = 0,
        activity: [ChatActivityItem] = []
    ) -> ChatTurnState {
        ChatTurnState(
            id: id,
            prompt: "q",
            promptPending: false,
            answer: "",
            answerStreaming: running,
            running: running,
            activity: activity,
            startedAtMs: startedAtMs
        )
    }

    private func reasoning(_ id: String = "r1", done: Bool = false) -> ChatActivityItem {
        ChatActivityItem(id: id, kind: .reasoning(text: "check the write path first", done: done))
    }

    private func tool(
        _ id: String = "tool:call_1",
        name: String = "run_command",
        summary: String? = "pytest -q",
        status: ToolRunStatus = .running,
        detail: String? = nil
    ) -> ChatActivityItem {
        ChatActivityItem(id: id, kind: .tool(name: name, summary: summary, status: status, detail: detail))
    }

    private func edit(path: String = "tool-probe.txt", status: ToolRunStatus = .done) -> ChatActivityItem {
        ChatActivityItem(
            id: "file:call_2",
            kind: .fileEdit(
                FileEditState(
                    path: path,
                    status: status,
                    added: 1,
                    deleted: 0,
                    operation: "create",
                    diff: "--- tool-probe.txt\n+++ tool-probe.txt\n@@ -0,0 +1 @@\n+nanobot"
                )
            )
        )
    }

    /// The plan's red line: one cluster per turn, and a turn that has finished folds it.
    @Test func groupsByTurnAndCollapses() {
        let model = ActivityViewModel(now: { 7_000 })

        model.update(thread: ChatThreadState(turns: [
            turn(id: "t1", running: true, activity: [reasoning(), tool()]),
            turn(id: "t2", running: false, activity: [reasoning("r2", done: true), tool("tool:call_9", status: .done)]),
        ]))

        #expect(model.turnIds == ["t1", "t2"], "one cluster per turn, in thread order")
        #expect(model.cluster(for: "t1")?.isOpen == true, "a running turn shows what it is doing")
        #expect(model.cluster(for: "t2")?.isOpen == false, "a finished turn folds its activity away")
        #expect(model.cluster(for: "t1")?.items.count == 2)
        #expect(model.cluster(for: "missing") == nil, "a turn with no activity has no cluster at all")
    }

    @Test func aTurnWithNoActivityHasNoCluster() {
        let model = ActivityViewModel(now: { 0 })
        model.update(thread: ChatThreadState(turns: [turn(activity: [])]))
        #expect(model.turnIds.isEmpty)
    }

    @Test func aFailedRowKeepsTheClusterOpen() {
        let model = ActivityViewModel(now: { 0 })
        model.update(thread: ChatThreadState(turns: [
            turn(running: false, activity: [tool(status: .failed, detail: "exit 4")]),
        ]))

        let cluster = try? #require(model.cluster(for: "t1"))
        #expect(cluster?.isOpen == true, "a failure the reader has not seen must not be folded away")
        #expect(cluster?.hasFailure == true)
        #expect(cluster?.statusText == "failed")
    }

    /// The design's own headline: `Thought for 8s · 4 steps · 2 files changed`.
    @Test func theHeadlineCountsStepsAndFiles() {
        // The clock is the injected one; the turn started eight seconds before it.
        let model = ActivityViewModel(now: { 15_000 })
        model.update(thread: ChatThreadState(turns: [
            turn(running: false, startedAtMs: 7_000, activity: [
                reasoning(done: true),
                tool("tool:1", status: .done),
                tool("tool:2", status: .done),
                edit(),
            ]),
        ]))

        let cluster = model.cluster(for: "t1")
        #expect(cluster?.statusText == "done")
        #expect(cluster?.headline == "Thought for 8s · 3 steps · 1 file changed")

        model.update(thread: ChatThreadState(turns: [
            turn(running: false, startedAtMs: 7_000, activity: [tool("tool:1", status: .done), tool("tool:2", status: .done)]),
        ]))
        #expect(model.cluster(for: "t1")?.headline == "Thought for 8s · 2 steps", "no files changed, no clause for it")

        model.update(thread: ChatThreadState(turns: [turn(running: true, activity: [tool()])]))
        #expect(model.cluster(for: "t1")?.headline == "Thinking", "a running turn says what it is doing, not how long")
    }

    @Test func aFileEditShowsItsDiffLinesWithCounts() {
        let model = ActivityViewModel(now: { 0 })
        model.update(thread: ChatThreadState(turns: [turn(activity: [edit()])]))

        guard case let .fileEdit(state)? = model.cluster(for: "t1")?.items.first?.kind else {
            Issue.record("the row is a file edit")
            return
        }
        #expect(state.added == 1)
        #expect(state.deleted == 0)
        #expect(state.lines.map(\.kind) == [.hunk, .added], "the ---/+++ headers are not rows")
        #expect(state.lines.map(\.text) == ["@@ -0,0 +1 @@", "+nanobot"], "a diff line keeps its marker")
        #expect(state.lines.last?.body == "nanobot", "and the body is what the row draws")
    }

    @Test func searchSourcesBecomeChips() {
        let model = ActivityViewModel(now: { 0 })
        let search = ChatActivityItem(
            id: "search:1",
            kind: .search(query: "sqlite wal batching", sources: ["https://sqlite.org/wal.html", "https://github.com/omnilib/aiosqlite/issues/122"])
        )
        model.update(thread: ChatThreadState(turns: [turn(activity: [search])]))

        guard case let .search(query, sources)? = model.cluster(for: "t1")?.items.first?.kind else {
            Issue.record("the row is a search")
            return
        }
        #expect(query == "sqlite wal batching")
        #expect(sources.count == 2)
        #expect(model.cluster(for: "t1")?.sourceChips.map(\.label) == ["sqlite.org · wal.html", "github.com · issues/122"],
                "a chip is host · path tail, which is what the design draws")
    }

    @Test func togglingReopensACollapsedCluster() {
        let model = ActivityViewModel(now: { 0 })
        model.update(thread: ChatThreadState(turns: [turn(running: false, activity: [tool(status: .done)])]))
        #expect(model.cluster(for: "t1")?.isOpen == false)

        model.toggle(turnId: "t1")
        #expect(model.cluster(for: "t1")?.isOpen == true, "a tap opens what the fold hid")

        model.toggle(turnId: "t1")
        #expect(model.cluster(for: "t1")?.isOpen == false)
    }

    @Test func aFinishedTurnDoesNotOverwriteTheReadersChoice() {
        let model = ActivityViewModel(now: { 0 })
        model.update(thread: ChatThreadState(turns: [turn(running: true, activity: [tool()])]))
        model.toggle(turnId: "t1")
        #expect(model.cluster(for: "t1")?.isOpen == false)

        // The same turn settles: the reader had folded it on purpose, so it stays folded.
        model.update(thread: ChatThreadState(turns: [turn(running: false, activity: [tool(status: .done)])]))
        #expect(model.cluster(for: "t1")?.isOpen == false)
    }

    /// T9's first complaint: a row printed its arguments and its output. The web draws one line —
    /// glyph, name, one-line summary — and keeps the rest behind the row's disclosure, closed by
    /// default (`ActivityStep.tsx:77`; the details were a hover tooltip there, a tap here).
    @Test func rowDetailsStartClosed() {
        let model = ActivityViewModel(now: { 0 })
        model.update(thread: ChatThreadState(turns: [
            turn(activity: [
                reasoning(),
                tool(summary: "pytest -q", detail: "collected 12 items"),
                edit(),
            ]),
        ]))

        let items = model.cluster(for: "t1")?.items ?? []
        #expect(items.count == 3)
        #expect(items.allSatisfy { $0.detailsOpen == false }, "every row arrives folded: the row is one line")
    }

    @Test func tappingARowOpensItsDetails() {
        let model = ActivityViewModel(now: { 0 })
        model.update(thread: ChatThreadState(turns: [turn(activity: [tool()])]))

        model.toggleDetails(itemId: "tool:call_1")
        #expect(model.cluster(for: "t1")?.items.first?.detailsOpen == true, "a tap opens what the row hid")

        model.toggleDetails(itemId: "tool:call_1")
        #expect(model.cluster(for: "t1")?.items.first?.detailsOpen == false)
    }

    /// `compactReasoningPreview` (webui `reasoning-preview.ts:1`): the row is one line of prose.
    @Test func aReasoningRowIsOneLine() {
        let model = ActivityViewModel(now: { 0 })
        let item = ChatActivityItem(
            id: "r1",
            kind: .reasoning(text: "## Check the **write path**\n\n   first, see [flush](x.md)", done: false)
        )
        model.update(thread: ChatThreadState(turns: [turn(activity: [item])]))

        #expect(model.cluster(for: "t1")?.items.first?.preview == "Check the write path first, see flush",
                "markdown is compacted away and the line is a line, not a document")
        #expect(model.cluster(for: "t1")?.items.first?.detailsOpen == false, "and it is folded like every other row")
    }

    @Test func aRowsDetailsAreForgottenWithItsTurn() {
        let model = ActivityViewModel(now: { 0 })
        model.update(thread: ChatThreadState(turns: [turn(activity: [tool()])]))
        model.toggleDetails(itemId: "tool:call_1")

        model.update(thread: ChatThreadState(turns: []))
        model.update(thread: ChatThreadState(turns: [turn(activity: [tool()])]))

        #expect(model.cluster(for: "t1")?.items.first?.detailsOpen == false,
                "a row out of the thread has nothing left to remember")
    }
}
