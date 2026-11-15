package de.arjmandi.nanobot.companion.projection

/**
 * The document a platform draws while an answer is still arriving (C4.8).
 *
 * The parser is a whole-document parser, so the only question is which part of the *last* block
 * cannot be trusted yet. For prose there is nothing to do: a half-written `**bold` marker is a
 * literal asterisk in the parser and in the browser's own renderer, so the text can be drawn as it
 * arrives. A closed code fence ends itself. A table is the case that does break: its final row is
 * one cell wide until the last cell lands, and a table drawn then would grow a row as a reader
 * watches. While the turn is open that row is dropped; once the text is final it is padded to the
 * header like any other row.
 *
 * This used to also split a trailing paragraph into stable/unstable halves. Nothing called that
 * pair — the facade has always taken the whole document — so the split was tested code that
 * production never ran, and its test could not fail for a reason a reader would notice. It is
 * gone; `document` is what the apps draw and what the tests exercise.
 */
internal object MarkdownStream {

    /** The document a platform draws: the whole parse, less whatever the next delta can change. */
    fun document(text: String, streaming: Boolean): MarkdownDocument {
        val blocks = MarkdownParser.parse(text).blocks
        if (!streaming) return MarkdownDocument(blocks)
        val last = blocks.lastOrNull()
        return if (last is MarkdownBlock.Table) {
            MarkdownDocument(blocks.dropLast(1) + last.withoutTheRowStillArriving())
        } else {
            MarkdownDocument(blocks)
        }
    }

    /**
     * The final row of a streaming table is the one being typed. It is dropped when it has no
     * text at all or when it is a row the reader can already read — a cell that is complete
     * enough to draw stays, so the table grows a row at a time rather than all at once.
     */
    private fun MarkdownBlock.Table.withoutTheRowStillArriving(): MarkdownBlock.Table {
        val last = rows.lastOrNull() ?: return this
        val typed = last.map { it.plainText() }
        val emptyTail = typed.dropLast(1).any { it.isEmpty() } || typed.lastOrNull().orEmpty().isEmpty()
        return if (emptyTail && last.dropLast(1).all { it.isNotEmpty() }) copy(rows = rows.dropLast(1)) else this
    }
}
