package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.projection.Inline
import de.arjmandi.nanobot.companion.projection.ListItem
import de.arjmandi.nanobot.companion.projection.MarkdownBlock
import de.arjmandi.nanobot.companion.projection.MarkdownDocument
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The construct matrix: one row per markdown construct the WebUI's renderer handles, and what this
 * parser makes of it. `webui/src/components/MarkdownTextRenderer.tsx` is where the list comes from —
 * `remark-gfm` (tables, task items, strikethrough, autolinks), `remark-breaks`, `streamdown`'s
 * block splitting, and the `mermaid` fence the code-block switch looks for — so a row exists here
 * because the browser draws it, not because markdown happens to have it.
 *
 * Every row is written as the tree the platform should receive, in one line. A group collects all
 * its failures and reports them together, because the useful output is the whole matrix, not the
 * first row that disagrees.
 */
class MarkdownMatrixTest {

    private fun matrix(name: String, rows: Matrix.() -> Unit) {
        val matrix = Matrix()
        matrix.rows()
        matrix.assertOk(name)
    }

    private class Matrix {
        private val failures = mutableListOf<String>()

        fun row(name: String, markdown: String, expected: String, streaming: Boolean = false) {
            val actual = markdownDocument(markdown, streaming).shape()
            if (actual != expected) {
                failures += "$name\n    input:    ${markdown.replace("\n", "\\n")}" +
                    "\n    expected: $expected\n    actual:   $actual"
            }
        }

        fun assertOk(group: String) =
            assertTrue(failures.isEmpty(), "$group: ${failures.size} construct(s) differ\n\n" + failures.joinToString("\n\n"))
    }

    // ------------------------------------------------------------------ tables

    @Test
    fun tables() = matrix("tables") {
        row(
            "inline marks in cells",
            """
            | **risk** | `where` |
            | --- | --- |
            | a | 1 |
            """.trimIndent(),
            "table(**risk**|`where`;a|1)",
        )
        row(
            "an escaped pipe stays inside its cell",
            """
            | col | other |
            | --- | --- |
            | a \| b | c |
            """.trimIndent(),
            "table(col|other;a | b|c)",
        )
        row(
            "a still-arriving row is padded to the header",
            """
            | a | b | c |
            | --- | --- | --- |
            | 1 | 2 |
            """.trimIndent(),
            "table(a|b|c;1|2|)",
        )
        row(
            "a row wider than the header is cut to it",
            """
            | a | b |
            | --- | --- |
            | 1 | 2 | 3 |
            """.trimIndent(),
            "table(a|b;1|2)",
        )
        row(
            "no outer pipes",
            "a | b\n--- | ---\n1 | 2",
            "table(a|b;1|2)",
        )
        row(
            "an empty cell is a cell",
            "| a | b |\n| --- | --- |\n|   | 2 |",
            "table(a|b;|2)",
        )
        row(
            "alignment is the delimiter row's",
            "| l | c | r |\n|:--|:-:|--:|\n| 1 | 2 | 3 |",
            "table(l|c|r;1|2|3)",
        )
    }

    // --------------------------------------------------------------- code fences

    @Test
    fun codeFences() = matrix("fences") {
        row(
            "an info string is a language, not the whole line",
            "```kotlin title=\"Thing.kt\"\nval x = 1\n```",
            "code(kotlin:val x = 1)",
        )
        row(
            "a mermaid block keeps its source and its language",
            "```mermaid\ngraph TD\n  A-->B\n```",
            "code(mermaid:graph TD\n  A-->B)",
        )
        row("a tilde fence can hold backticks", "~~~\n```\n~~~", "code(-:```)")
        row("an unclosed fence is still code, and says it is open", "```kotlin\nval x = 1", "code(kotlin,open:val x = 1)")
    }

    // ------------------------------------------------------------- inline marks

    @Test
    fun inlineMarks() = matrix("inline") {
        row("a double-backtick span holds a backtick", "Use ``a `b` c`` now", "p(Use `a `b` c` now)")
        row("code is not emphasis", "`**not bold**`", "p(`**not bold**`)")
        row("strikethrough", "~~gone~~ but ~kept~", "p(~~gone~~ but ~kept~)")
        row("an intraword underscore is an identifier", "call a snake_case_name now", "p(call a snake_case_name now)")
        row("triple emphasis", "***both***", "p(***both***)")
        row("emphasis around strong", "**bold and *italic* inside**", "p(**bold and *italic* inside**)")
        row("strong inside emphasis", "*italic **and bold** inside*", "p(*italic **and bold** inside*)")
    }

    // ----------------------------------------------------------------- headings

    @Test
    fun headings() = matrix("headings") {
        row("a closing hash run is not text", "## Title ##", "h2(Title)")
        row("marks inside a heading", "# Use `x` **now**", "h1(Use `x` **now**)")
        row("six hashes", "###### deep", "h6(deep)")
        row("a hashtag is not a heading", "#hashtag", "p(#hashtag)")
    }

    // -------------------------------------------------------------------- lists

    @Test
    fun lists() = matrix("lists") {
        row("three levels, two spaces a level", "- a\n  - b\n    - c", "list(u;0:a,1:b,2:c)")
        row("four spaces is one level, not two", "- a\n    - b", "list(u;0:a,1:b)")
        // The item's own line breaks stay breaks, because `remark-breaks` turns every newline
        // in the WebUI into one too: a wrapped bullet looks the same in the browser and here.
        row(
            "a continuation line belongs to its item",
            "- first line\n  continues here\n- second",
            "list(u;0:first line\ncontinues here,0:second)",
        )
        row("an ordered list starts where it says", "3. three\n4. four", "list(o3;0:three,0:four)")
        row(
            "a nested ordered list inside a bullet list is its own block",
            "- bullet\n  1. one\n  2. two",
            "list(u;0:bullet) / list(o1;1:one,1:two)",
        )
        row("task items", "- [ ] open\n- [x] done", "list(u;0:[ ]open,0:[x]done)")
    }

    // ---------------------------------------------------------------- blockquote

    @Test
    fun quotes() = matrix("quotes") {
        row("a quote holding a list", "> - a\n> - b", "quote(list(u;0:a,0:b))")
        row("a blank quote line is a paragraph break inside one quote", "> one\n>\n> two", "quote(p(one) / p(two))")
    }

    // -------------------------------------------------------------------- links

    @Test
    fun links() = matrix("links") {
        row("an angle autolink", "<https://example.com/x>", "p([https://example.com/x](https://example.com/x))")
        row("a bare url is a link", "see https://example.com/x now", "p(see [https://example.com/x](https://example.com/x) now)")
        row("a link title is not part of the destination", "[docs](https://example.com \"Title\")", "p([docs](https://example.com))")
        row("a link is not ended by a bracket in its text", "[a [b] c](https://e.com)", "p([a [b] c](https://e.com))")
    }

    // ------------------------------------------------------------------ streaming

    @Test
    fun theLastBlockOfAStreamingReply() = matrix("streaming") {
        // The case a reader watches most: the table is nearly here, one row still arriving.
        val midCell = "| Name | Value |\n|------|-------|\n| x    | 1     |\n| y    |"
        row(
            "the row still being typed is not a row yet",
            midCell,
            "table(Name|Value;x|1)",
            streaming = true,
        )
        row(
            "the same text is final once the turn ends",
            midCell,
            "table(Name|Value;x|1;y|)",
            streaming = false,
        )
        row(
            "a row that is complete is a row, row by row",
            "| Name | Value |\n|------|-------|\n| x    | 1     |\n| y    | 2     |",
            "table(Name|Value;x|1;y|2)",
            streaming = true,
        )
        row(
            "a header whose delimiter is half-arrived is prose",
            "| Name | Value |\n|------",
            "p(| Name | Value |\n|------)",
            streaming = true,
        )
        row(
            "a half-written fence is code with no copy yet",
            "```mermaid\ngraph TD\n  A-->",
            "code(mermaid,open:graph TD\n  A-->)",
            streaming = true,
        )
    }

    // ------------------------------------------------------------ out of scope

    @Test
    fun constructsOutsideTheSubsetStayReadable() = matrix("out of scope") {
        // Each of these is drawn by the WebUI and not by this client. The expectation is that
        // nothing is thrown away: the reader gets the characters, not a silently empty block.
        row("math stays literal", "the cost is \$x^2\$ per unit", "p(the cost is \$x^2\$ per unit)")
        row("an image stays its own text", "![alt](https://example.com/a.png)", "p(![alt](https://example.com/a.png))")
        row("setext underline reads as prose and a rule", "Title\n---", "p(Title) / rule")
        row("an html tag is text", "<span>raw</span>", "p(<span>raw</span>)")
    }
}

/** One line per document: block kind, and the marks that survived, so a diff is readable. */
private fun MarkdownDocument.shape(): String = blocks.joinToString(" / ") { it.shape() }

private fun MarkdownBlock.shape(): String = when (this) {
    is MarkdownBlock.Heading -> "h$level(${inlines.shape()})"
    is MarkdownBlock.Paragraph -> "p(${inlines.shape()})"
    is MarkdownBlock.ListBlock -> "list(${if (ordered) "o$start" else "u"};" +
        items.joinToString(",") { it.shape() } + ")"
    is MarkdownBlock.CodeFence -> "code(${language ?: "-"}${if (closed) "" else ",open"}:$code)"
    is MarkdownBlock.Table -> "table(" + header.joinToString("|") { it.shape() } + ";" +
        rows.joinToString(";") { row -> row.joinToString("|") { it.shape() } } + ")"
    is MarkdownBlock.Quote -> "quote(${blocks.joinToString(" / ") { it.shape() }})"
    MarkdownBlock.Rule -> "rule"
}

private fun ListItem.shape(): String = "$depth:" + (when (checked) {
    true -> "[x]"
    false -> "[ ]"
    null -> ""
}) + inlines.shape()

private fun List<Inline>.shape(): String = joinToString("") { inline ->
    when (inline) {
        is Inline.Text -> inline.text
        is Inline.Code -> "`${inline.text}`"
        is Inline.Emphasis -> "*${inline.inlines.shape()}*"
        is Inline.Strong -> "**${inline.inlines.shape()}**"
        is Inline.Strikethrough -> "~~${inline.inlines.shape()}~~"
        is Inline.Link -> "[${inline.inlines.shape()}](${inline.destination})"
    }
}
