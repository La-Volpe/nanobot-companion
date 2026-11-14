package de.arjmandi.nanobot.companion.projection

/**
 * Splitting a streaming reply into what can be rendered as markdown and what cannot yet
 * (plan.md C4.8). The parser is a whole-document parser, so the split is a rule about the
 * *last* block: a block is stable once nothing that arrives later can change it.
 *
 * Two ways to be stable. A closed code fence ends itself. Anything else is only final once
 * a blank line follows it, because until then the next delta can still extend the same
 * line — and rendering half a bold marker as bold is exactly the half-rendered reply the
 * risk table warns about.
 */
internal object MarkdownStream {

    fun stableBlocks(text: String): List<MarkdownBlock> {
        val blocks = MarkdownParser.parse(text).blocks
        val last = blocks.lastOrNull() ?: return emptyList()
        return if (isFinal(text, last)) blocks else blocks.dropLast(1)
    }

    fun unstableTail(text: String): MarkdownBlock? {
        val last = MarkdownParser.parse(text).blocks.lastOrNull() ?: return null
        return if (isFinal(text, last)) null else last
    }

    private fun isFinal(text: String, block: MarkdownBlock): Boolean = when {
        block is MarkdownBlock.CodeFence -> block.closed
        else -> text.endsWith("\n\n")
    }
}
