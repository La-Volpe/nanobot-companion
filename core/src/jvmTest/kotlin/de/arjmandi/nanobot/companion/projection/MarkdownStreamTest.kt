package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.fixture.Fixtures
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reply arrives a few characters at a time, and the document a platform draws has to stay
 * readable while that happens (C4.8, risk table "streaming markdown half-rendering").
 *
 * These cases test `document`, which is what the facade hands the apps. They used to test a
 * `stableBlocks`/`unstableTail` pair that nothing in production called — a test that could not
 * fail for a reason a reader would ever see. The rule they pin down now is the one that exists:
 * prose is drawn as it arrives (the parser already renders an unclosed marker as a literal
 * character, which is what the browser does too), and only a table's in-flight row is withheld.
 */
class MarkdownStreamTest {

    private fun blocks(text: String, streaming: Boolean): List<MarkdownBlock> =
        MarkdownStream.document(text, streaming).blocks

    @Test
    fun aHalfWrittenParagraphIsDrawnAsItArrives() {
        val partial = "Hello wor"
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(Inline.Text("Hello wor")))),
            blocks(partial, streaming = true),
            "an open paragraph is what the reader is watching: withholding it would leave a blank thread",
        )
    }

    @Test
    fun anUnclosedMarkerStaysLiteralUntilItCloses() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(Inline.Text("a **bo")))),
            blocks("a **bo", streaming = true),
            "half a bold marker is a literal asterisk, not bold of nothing",
        )
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(Inline.Text("a "), Inline.Strong(listOf(Inline.Text("bo")))))),
            blocks("a **bo**", streaming = false),
        )
    }

    @Test
    fun anOpenFenceIsStillACodeBlockWithItsText() {
        val open = blocks("```kotlin\nval x = 1", streaming = true)
        val fence = open.single() as MarkdownBlock.CodeFence
        assertEquals("val x = 1", fence.code)
        assertEquals("kotlin", fence.language)
        assertTrue(!fence.closed, "the fence says it is still open, so the renderer can hold the copy button")
    }

    @Test
    fun aClosedFenceIsFinal() {
        val closed = blocks("```\nval x = 1\n```", streaming = true)
        assertEquals("val x = 1", (closed.single() as MarkdownBlock.CodeFence).code)
        assertTrue((closed.single() as MarkdownBlock.CodeFence).closed)
    }

    @Test
    fun aTableDropsTheRowStillBeingTyped() {
        val streaming = """
            | a | b |
            | --- | --- |
            | 1 | 2 |
            | 3 |
        """.trimIndent()
        val table = blocks(streaming, streaming = true).single() as MarkdownBlock.Table
        assertEquals(1, table.rows.size, "the half-typed row is not drawn yet")

        val settled = blocks(streaming, streaming = false).single() as MarkdownBlock.Table
        assertEquals(2, settled.rows.size, "once the turn ends the row is real and padded to the header")
        assertEquals(2, settled.rows.last().size, "a short row is padded, not left one cell wide")
    }

    @Test
    fun everyPrefixOfARealReplyParses() {
        // Fuzz over a real transcript (plan.md C4.8): 200 prefixes of the recorded answer,
        // none of which may throw. A chat client that crashes on a half-written reply is
        // worse than one that shows it as plain text.
        val answer = recordedAnswer()
        assertTrue(answer.length > 100, "the recorded reply is too short to fuzz; got ${answer.length} chars")
        // The corpus is only worth fuzzing if it is real markdown: the recorded reply has
        // the four constructs v1 renders.
        val blocks = MarkdownParser.parse(answer).blocks
        assertTrue(
            blocks.any { it is MarkdownBlock.CodeFence } && blocks.any { it is MarkdownBlock.Table } &&
                blocks.any { it is MarkdownBlock.ListBlock } && blocks.any { it is MarkdownBlock.Heading },
            "the recorded markdown reply did not parse into heading/list/table/fence: $blocks",
        )
        val random = Random(seed = 7)

        repeat(200) {
            val cut = random.nextInt(answer.length + 1)
            MarkdownStream.document(answer.take(cut), streaming = true)
            MarkdownStream.document(answer.take(cut), streaming = false)
        }
    }

    private fun recordedAnswer(): String {
        val deltas = Fixtures.scenarioFrames("markdown")
            .filter { (it["event"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "delta" }
        return deltas.joinToString("") {
            (it["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        }
    }
}
