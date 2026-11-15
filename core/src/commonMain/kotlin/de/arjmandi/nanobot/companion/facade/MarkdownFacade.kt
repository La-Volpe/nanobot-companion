package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.projection.MarkdownDocument
import de.arjmandi.nanobot.companion.projection.MarkdownStream

/**
 * The markdown tree a platform renders (plan.md D6, C4.7, I5). Rendering is the platform's job
 * and parsing is this side's, so the tree has to cross the facade: a screen cannot import the
 * projection package, and an internal document would be a contract with no way to read it.
 *
 * `streaming` says the turn is still arriving, so the last block may be half-written: the tree
 * then holds back what the next delta can still change (C4.8). A finished turn passes false, and
 * the same text is final.
 */
public fun markdownDocument(text: String, streaming: Boolean = false): MarkdownDocument =
    MarkdownStream.document(text, streaming)
