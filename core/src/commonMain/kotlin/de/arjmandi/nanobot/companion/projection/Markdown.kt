package de.arjmandi.nanobot.companion.projection

/**
 * The subset of markdown the WebUI renders, as a tree `:core` owns (plan.md D6/C4.7).
 * Rendering belongs to the platform, so this file is the whole contract between them:
 * a block list, and inline spans inside each block.
 *
 * Depth-based list items rather than nested block lists: a list is a flat sequence of
 * items that each know how deep they are, which is what a streaming parser can build
 * incrementally and what both SwiftUI and Compose indent from one number.
 */
internal data class MarkdownDocument(val blocks: List<MarkdownBlock>) {

    /** Everything a copy button or a test wants: the text with no markup. */
    val plainText: String get() = blocks.joinToString("\n") { it.plainText() }
}

internal sealed interface MarkdownBlock {

    data class Heading(val level: Int, val inlines: List<Inline>) : MarkdownBlock

    data class Paragraph(val inlines: List<Inline>) : MarkdownBlock

    data class ListBlock(val ordered: Boolean, val items: List<ListItem>, val start: Int = 1) : MarkdownBlock

    /** `closed` is false while a fence is still arriving, which the streaming split reads. */
    data class CodeFence(val language: String?, val code: String, val closed: Boolean = true) : MarkdownBlock

    data class Table(
        val header: List<List<Inline>>,
        val alignments: List<Alignment>,
        val rows: List<List<List<Inline>>>,
    ) : MarkdownBlock

    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    data object Rule : MarkdownBlock
}

/** One list item: how deep it sits, and its own inline content. */
internal data class ListItem(val depth: Int, val inlines: List<Inline>)

internal enum class Alignment { None, Left, Center, Right }

internal sealed interface Inline {

    data class Text(val text: String) : Inline

    data class Code(val text: String) : Inline

    data class Emphasis(val inlines: List<Inline>) : Inline

    data class Strong(val inlines: List<Inline>) : Inline

    data class Link(val inlines: List<Inline>, val destination: String) : Inline
}

internal fun MarkdownBlock.plainText(): String = when (this) {
    is MarkdownBlock.Heading -> inlines.plainText()
    is MarkdownBlock.Paragraph -> inlines.plainText()
    is MarkdownBlock.ListBlock -> items.joinToString("\n") { it.inlines.plainText() }
    is MarkdownBlock.CodeFence -> code
    is MarkdownBlock.Table -> (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(" | ") { it.plainText() } }
    is MarkdownBlock.Quote -> blocks.joinToString("\n") { it.plainText() }
    MarkdownBlock.Rule -> ""
}

internal fun List<Inline>.plainText(): String = joinToString("") { inline ->
    when (inline) {
        is Inline.Text -> inline.text
        is Inline.Code -> inline.text
        is Inline.Emphasis -> inline.inlines.plainText()
        is Inline.Strong -> inline.inlines.plainText()
        is Inline.Link -> inline.inlines.plainText()
    }
}
