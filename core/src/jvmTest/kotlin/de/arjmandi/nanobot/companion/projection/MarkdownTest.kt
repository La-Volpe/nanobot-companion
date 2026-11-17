package de.arjmandi.nanobot.companion.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The markdown parser is `:core`'s job because both platforms must agree on what a reply means.
 * Nothing here renders: it turns the gateway's text into a tree, and these tests are the
 * specification of which markdown is in v1.
 *
 * Not in the subset, by decision: math and mermaid, setext headings, reference links, HTML
 * passthrough, footnotes. A construct outside the subset stays literal text, which is the
 * failure a reader can still read.
 */
class MarkdownTest {

    @Test
    fun parsesHeadingsListsTablesCodeFences() {
        val document = MarkdownParser.parse(
            """
            # Title

            Intro sentence.

            - first
            - second

            1. one
            2. two

            | Name | Size |
            | --- | ---: |
            | a | 1 |

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )

        val headings = document.blocks.filterIsInstance<MarkdownBlock.Heading>()
        assertEquals(1, headings.size)
        assertEquals(1, headings.single().level)
        assertEquals("Title", headings.single().inlines.plainText())

        val paragraphs = document.blocks.filterIsInstance<MarkdownBlock.Paragraph>()
        assertEquals("Intro sentence.", paragraphs.single().inlines.plainText())

        val lists = document.blocks.filterIsInstance<MarkdownBlock.ListBlock>()
        assertEquals(2, lists.size, "a bullet list and an ordered list are two blocks")
        assertEquals(listOf("first", "second"), lists[0].items.map { it.inlines.plainText() })
        assertEquals(false, lists[0].ordered)
        assertEquals(listOf("one", "two"), lists[1].items.map { it.inlines.plainText() })
        assertEquals(true, lists[1].ordered)

        val table = document.blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("Name", "Size"), table.header.map { it.plainText() })
        assertEquals(listOf(Alignment.None, Alignment.Right), table.alignments, "the delimiter row sets alignment")
        assertEquals(listOf(listOf("a", "1")), table.rows.map { row -> row.map { it.plainText() } })

        val fence = document.blocks.filterIsInstance<MarkdownBlock.CodeFence>().single()
        assertEquals("kotlin", fence.language)
        assertEquals("val x = 1", fence.code, "the fence body keeps its lines and drops the delimiters")
        assertTrue(fence.closed)
    }

    @Test
    fun nestedListsAndBlockquote() {
        val document = MarkdownParser.parse(
            """
            - outer
              - inner
            - outer again

            > quoted line
            > second line
            """.trimIndent(),
        )

        val list = document.blocks.filterIsInstance<MarkdownBlock.ListBlock>().single()
        assertEquals(
            listOf(0 to "outer", 1 to "inner", 0 to "outer again"),
            list.items.map { it.depth to it.inlines.plainText() },
            "indentation is the depth a platform indents by",
        )

        val quote = document.blocks.filterIsInstance<MarkdownBlock.Quote>().single()
        assertEquals("quoted line\nsecond line", quote.blocks.joinToString("\n") { it.plainText() })
    }

    @Test
    fun inlineCodeLinksBoldItalic() {
        val document = MarkdownParser.parse("Use `printf` **now** or *never*, see [docs](https://example.com).")

        val inlines = document.blocks.filterIsInstance<MarkdownBlock.Paragraph>().single().inlines

        assertEquals(Inline.Code("printf"), inlines.filterIsInstance<Inline.Code>().single())
        assertEquals("now", inlines.filterIsInstance<Inline.Strong>().single().inlines.plainText())
        assertEquals("never", inlines.filterIsInstance<Inline.Emphasis>().single().inlines.plainText())
        val link = inlines.filterIsInstance<Inline.Link>().single()
        assertEquals("docs", link.inlines.plainText())
        assertEquals("https://example.com", link.destination)
        assertEquals(
            "Use printf now or never, see docs.",
            inlines.plainText(),
            "plain text is the whole line with the markup removed",
        )
    }

    @Test
    fun unclosedFenceIsCodeUntilClose() {
        val document = MarkdownParser.parse(
            """
            before

            ```kotlin
            val x = 1
            """.trimIndent(),
        )

        val fence = document.blocks.filterIsInstance<MarkdownBlock.CodeFence>().single()
        assertEquals(false, fence.closed, "a fence that never closed is still a fence, and says so")
        assertEquals("val x = 1", fence.code, "the tail of a streaming reply is code, not prose")
        assertEquals("before", document.blocks.filterIsInstance<MarkdownBlock.Paragraph>().single().inlines.plainText())
    }

    @Test
    fun tableWithoutAlignmentRowIsParagraph() {
        val document = MarkdownParser.parse(
            """
            | not | a table |
            | without | delimiter |
            """.trimIndent(),
        )

        assertTrue(document.blocks.filterIsInstance<MarkdownBlock.Table>().isEmpty(), "a pipe block with no delimiter row is prose")
        assertEquals(1, document.blocks.filterIsInstance<MarkdownBlock.Paragraph>().size)
    }
}
