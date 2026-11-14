package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.projection.MarkdownDocument
import de.arjmandi.nanobot.companion.projection.MarkdownParser

/**
 * The markdown tree a platform renders (plan.md D6, C4.7, I5). Rendering is the platform's job
 * and parsing is this side's, so the tree has to cross the facade: a screen cannot import the
 * projection package, and an internal document would be a contract with no way to read it.
 */
public fun markdownDocument(text: String): MarkdownDocument = MarkdownParser.parse(text)
