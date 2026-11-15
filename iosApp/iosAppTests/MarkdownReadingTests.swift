import Testing
@testable import iosApp

/// The Swift side of the markdown matrix: `:core` decides what a reply means
/// (`MarkdownMatrixTest`), and this is only the translation. What it proves is that the marks the
/// core now produces — a task item's box, strikethrough, and a table row still arriving — reach
/// the values the views draw instead of being dropped on the way across.
struct MarkdownReadingTests {

    @Test func everyMarkReachesTheViewTree() {
        let blocks = Reading.blocks(
            "- [x] shipped\n- [ ] pending\n\n~~gone~~ and a [link](https://example.com)"
        )

        guard let first = blocks.first, case let .list(_, _, items) = first else {
            Issue.record("the list did not survive the adapter: \(blocks)")
            return
        }
        #expect(items.map(\.checked) == [true, false], "a task item keeps its box")

        guard let last = blocks.last, case let .paragraph(inlines) = last else {
            Issue.record("the paragraph did not survive the adapter: \(blocks)")
            return
        }
        #expect(inlines.contains { if case .strikethrough = $0 { true } else { false } })
        #expect(inlines.contains { if case .link = $0 { true } else { false } })
    }

    @Test func aStreamingTurnHoldsBackTheRowStillArriving() {
        let table = "| Name | Value |\n|------|-------|\n| x    | 1     |\n| y    |"

        let streaming = Reading.blocks(table, streaming: true)
        guard let first = streaming.first, case let .table(_, _, rows) = first else {
            Issue.record("the table did not survive the adapter: \(streaming)")
            return
        }
        #expect(rows.count == 1, "the row being typed is not drawn")

        let finished = Reading.blocks(table, streaming: false)
        guard let first = finished.first, case let .table(_, _, settled) = first else {
            Issue.record("the table did not survive the adapter: \(finished)")
            return
        }
        #expect(settled.count == 2, "once the turn ends the same text is final")
    }

    @Test func aMermaidFenceIsItsSource() {
        let blocks = Reading.blocks("```mermaid\ngraph TD\n  A-->B\n```")

        guard let first = blocks.first, case let .code(language, code, closed) = first else {
            Issue.record("the fence did not survive the adapter: \(blocks)")
            return
        }
        #expect(language == "mermaid", "the fence stays a fence; which half a diagram shows is the core's rule")
        #expect(code == "graph TD\n  A-->B")
        #expect(closed)
    }
}
