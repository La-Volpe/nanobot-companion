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
public data class MarkdownDocument(public val blocks: List<MarkdownBlock>) {

    /** Everything a copy button or a test wants: the text with no markup. */
    public val plainText: String get() = blocks.joinToString("\n") { it.plainText() }
}

public sealed interface MarkdownBlock {

    public data class Heading(val level: Int, val inlines: List<Inline>) : MarkdownBlock

    public data class Paragraph(val inlines: List<Inline>) : MarkdownBlock

    public data class ListBlock(val ordered: Boolean, val items: List<ListItem>, val start: Int = 1) : MarkdownBlock

    /** `closed` is false while a fence is still arriving, which the streaming split reads. */
    public data class CodeFence(val language: String?, val code: String, val closed: Boolean = true) : MarkdownBlock

    public data class Table(
        val header: List<List<Inline>>,
        val alignments: List<Alignment>,
        val rows: List<List<List<Inline>>>,
    ) : MarkdownBlock

    public data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    public data object Rule : MarkdownBlock
}

/**
 * One list item: how deep it sits, and its own inline content. `checked` is non-null only for a
 * task item (`- [ ]`, `- [x]`), which is a markdown checkbox and not a list of its own: the
 * WebUI draws the box in the item's gutter.
 */
public data class ListItem(
    public val depth: Int,
    public val inlines: List<Inline>,
    public val checked: Boolean? = null,
)

public enum class Alignment { None, Left, Center, Right }

public sealed interface Inline {

    public data class Text(public val text: String) : Inline

    public data class Code(public val text: String) : Inline

    public data class Emphasis(public val inlines: List<Inline>) : Inline

    public data class Strong(public val inlines: List<Inline>) : Inline

    public data class Strikethrough(public val inlines: List<Inline>) : Inline

    public data class Link(public val inlines: List<Inline>, public val destination: String) : Inline
}

public fun MarkdownBlock.plainText(): String = when (this) {
    is MarkdownBlock.Heading -> inlines.plainText()
    is MarkdownBlock.Paragraph -> inlines.plainText()
    is MarkdownBlock.ListBlock -> items.joinToString("\n") { it.inlines.plainText() }
    is MarkdownBlock.CodeFence -> code
    is MarkdownBlock.Table -> (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(" | ") { it.plainText() } }
    is MarkdownBlock.Quote -> blocks.joinToString("\n") { it.plainText() }
    MarkdownBlock.Rule -> ""
}

public fun List<Inline>.plainText(): String = joinToString("") { inline ->
    when (inline) {
        is Inline.Text -> inline.text
        is Inline.Code -> inline.text
        is Inline.Emphasis -> inline.inlines.plainText()
        is Inline.Strong -> inline.inlines.plainText()
        is Inline.Strikethrough -> inline.inlines.plainText()
        is Inline.Link -> inline.inlines.plainText()
    }
}
