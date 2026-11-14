package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.fixture.Fixtures
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reply arrives a few characters at a time, and markdown only means something once its
 * block is finished (plan.md C4.8, risk table "streaming markdown half-rendering"). So a
 * block is rendered as markdown once it cannot grow any more, and the block still being
 * written is rendered as plain text until then.
 */
class MarkdownStreamTest {

    @Test
    fun tailBlockStaysUnstable() {
        val partial = "Hello wor"
        assertTrue(MarkdownStream.stableBlocks(partial).isEmpty(), "a half-written paragraph is not markdown yet")
        assertEquals(MarkdownBlock.Paragraph(listOf(Inline.Text("Hello wor"))), MarkdownStream.unstableTail(partial))

        val twoBlocks = "One\n\nTwo"
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(Inline.Text("One")))),
            MarkdownStream.stableBlocks(twoBlocks),
            "the block a blank line follows can no longer change",
        )
        assertEquals(MarkdownBlock.Paragraph(listOf(Inline.Text("Two"))), MarkdownStream.unstableTail(twoBlocks))
    }

    @Test
    fun paragraphFinalisesOnBlankLine() {
        val finished = "One\n\nTwo\n\n"

        assertEquals(2, MarkdownStream.stableBlocks(finished).size, "a trailing blank line closes the last paragraph")
        assertNull(MarkdownStream.unstableTail(finished), "nothing is still being written")
    }

    @Test
    fun fenceFinalisesOnClose() {
        val open = "```kotlin\nval x = 1"
        assertTrue(MarkdownStream.stableBlocks(open).isEmpty(), "an unclosed fence is not rendered as code yet")
        val openTail = MarkdownStream.unstableTail(open)
        assertEquals(false, (openTail as MarkdownBlock.CodeFence).closed, "the tail knows it is an open fence")

        val closed = "```kotlin\nval x = 1\n```"
        val blocks = MarkdownStream.stableBlocks(closed)
        assertEquals(1, blocks.size, "the closing fence ends the block, blank line or not")
        assertEquals("val x = 1", (blocks.single() as MarkdownBlock.CodeFence).code)
        assertNull(MarkdownStream.unstableTail(closed))
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
            MarkdownStream.stableBlocks(answer.take(cut))
            MarkdownStream.unstableTail(answer.take(cut))
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
