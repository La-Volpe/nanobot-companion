package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.projection.Alignment
import de.arjmandi.nanobot.companion.projection.MarkdownBlock
import de.arjmandi.nanobot.companion.projection.MarkdownDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The markdown tree a platform renders. The parser itself is covered by
 * `MarkdownTest`; what this asserts is that the tree is reachable from the facade, because a
 * screen cannot import the projection package, so an internal `MarkdownDocument` was a rendering
 * contract with no way to read it.
 */
class MarkdownFacadeTest {

    private fun blocks(text: String): List<MarkdownBlock> = markdownDocument(text).blocks

    @Test
    fun proseIsAParagraphWithItsInlines() {
        val paragraph = assertIs<MarkdownBlock.Paragraph>(blocks("Writes now go through a batch.").single())
        assertEquals("Writes now go through a batch.", paragraph.inlines.joinToString("") { inline ->
            assertIs<de.arjmandi.nanobot.companion.projection.Inline.Text>(inline).text
        })
    }

    @Test
    fun aFenceKeepsItsLanguageAndCode() {
        val fence = assertIs<MarkdownBlock.CodeFence>(blocks("```python\nprint(1)\n```").single())
        assertEquals("python", fence.language)
        assertEquals("print(1)", fence.code)
        assertEquals(true, fence.closed)
    }

    @Test
    fun aTableKeepsItsHeaderAlignmentsAndRows() {
        val text = """
            | risk | where |
            |:-----|------:|
            | crash | flush |
        """.trimIndent()
        val table = assertIs<MarkdownBlock.Table>(blocks(text).single())
        assertEquals(2, table.header.size)
        assertEquals(listOf(Alignment.Left, Alignment.Right), table.alignments)
        assertEquals(1, table.rows.size)
    }

    @Test
    fun aListKeepsItsDepths() {
        val list = assertIs<MarkdownBlock.ListBlock>(blocks("- one\n  - one point one").single())
        assertEquals(false, list.ordered)
        assertEquals(listOf(0, 1), list.items.map { it.depth })
    }

    @Test
    fun theDocumentsPlainTextIsTheMarkupFreeText() {
        val document: MarkdownDocument = markdownDocument("## Title\n\n**bold** and `code`")
        assertEquals("Title\nbold and code", document.plainText)
    }
}
