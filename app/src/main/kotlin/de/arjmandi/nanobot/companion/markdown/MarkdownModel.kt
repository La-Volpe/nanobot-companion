package de.arjmandi.nanobot.companion.markdown

/**
 * The markdown tree the chat draws (plan.md A5, D6), in Kotlin's own words. The core parses and
 * the bridge translates it here: rendering decisions stay on this side, and no composable sees a
 * projection type.
 */
data class MarkdownView(val blocks: List<MdBlock>) {
    /** Everything a copy button or a test wants: the text with no markup. */
    val plainText: String get() = blocks.joinToString("\n") { it.plainText }
}

sealed interface MdBlock {
    val plainText: String

    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock {
        override val plainText: String get() = inlines.plainText
    }

    data class Paragraph(val inlines: List<MdInline>) : MdBlock {
        override val plainText: String get() = inlines.plainText
    }

    /** Depth-based items rather than nested blocks, which is what the core hands over. */
    data class Bullets(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock {
        override val plainText: String get() = items.joinToString("\n") { it.inlines.plainText }
    }

    /** `closed` is false while a fence is still arriving, which the caret follows. */
    data class Code(val language: String?, val code: String, val closed: Boolean) : MdBlock {
        override val plainText: String get() = code

        /** Line numbers appear on a block with more than one line, as the design draws them. */
        val lines: List<String> get() = code.trimEnd('\n').split("\n")
    }

    data class Table(
        val header: List<List<MdInline>>,
        val alignments: List<MdAlignment>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock {
        override val plainText: String
            get() = (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(" | ") { it.plainText } }
    }

    data class Quote(val blocks: List<MdBlock>) : MdBlock {
        override val plainText: String get() = blocks.joinToString("\n") { it.plainText }
    }

    data object Rule : MdBlock {
        override val plainText: String get() = ""
    }
}

/** One item; `checked` is non-null only for a task item, whose box sits in the gutter. */
data class MdListItem(val depth: Int, val inlines: List<MdInline>, val checked: Boolean? = null)

enum class MdAlignment { None, Left, Center, Right }

sealed interface MdInline {
    val plainText: String

    data class Text(val text: String) : MdInline {
        override val plainText: String get() = text
    }

    data class Code(val text: String) : MdInline {
        override val plainText: String get() = text
    }

    data class Emphasis(val inlines: List<MdInline>) : MdInline {
        override val plainText: String get() = inlines.plainText
    }

    data class Strong(val inlines: List<MdInline>) : MdInline {
        override val plainText: String get() = inlines.plainText
    }

    data class Strikethrough(val inlines: List<MdInline>) : MdInline {
        override val plainText: String get() = inlines.plainText
    }

    data class Link(val inlines: List<MdInline>, val destination: String) : MdInline {
        override val plainText: String get() = inlines.plainText
    }
}

val List<MdInline>.plainText: String get() = joinToString("") { it.plainText }
