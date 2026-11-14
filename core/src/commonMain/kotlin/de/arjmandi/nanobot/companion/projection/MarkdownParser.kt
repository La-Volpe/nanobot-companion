package de.arjmandi.nanobot.companion.projection

/**
 * The parser (plan.md C4.7, D6). Two scanners, because the two grammars are different:
 * a block scanner consumes whole lines and decides what the next line means, and an
 * inline scanner walks one line's worth of text. Both are pure functions of their input,
 * which is what makes the streaming split (C4.8) an ordinary unit test.
 *
 * Constructs outside the v1 subset (D7) are never thrown away: an unrecognised line is
 * paragraph text, so a gateway feature this client lacks still reads as prose.
 */
internal object MarkdownParser {

    fun parse(text: String): MarkdownDocument = MarkdownDocument(BlockScanner(TextLines(text)).blocks())

    // ---------------------------------------------------------------- blocks

    private class TextLines(text: String) {
        val lines: List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split("\n").toMutableList()
        var index: Int = 0

        fun peek(): String? = lines.getOrNull(index)

        fun next(): String = lines[index++]

        fun done(): Boolean = index >= lines.size
    }

    private class BlockScanner(private val input: TextLines) {

        fun blocks(): List<MarkdownBlock> {
            val blocks = mutableListOf<MarkdownBlock>()
            while (!input.done()) {
                val line = input.peek()!!
                when {
                    line.isBlank() -> input.next()
                    isFence(line) -> blocks += fence()
                    isHeading(line) -> blocks += heading()
                    isRule(line) -> {
                        input.next()
                        blocks += MarkdownBlock.Rule
                    }
                    line.trimStart().startsWith(">") -> blocks += quote()
                    isTableStart() -> blocks += table()
                    isListItem(line) -> blocks += list()
                    else -> blocks += paragraph()
                }
            }
            return blocks
        }

        private fun isFence(line: String): Boolean =
            line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")

        private fun fence(): MarkdownBlock.CodeFence {
            val opener = input.next().trimStart()
            val marker = opener.take(3)
            val language = opener.removePrefix(marker).trim().ifEmpty { null }
            val body = mutableListOf<String>()
            var closed = false
            while (!input.done()) {
                val line = input.next()
                if (line.trim().startsWith(marker)) {
                    closed = true
                    break
                }
                body += line
            }
            return MarkdownBlock.CodeFence(language = language, code = body.joinToString("\n"), closed = closed)
        }

        private fun isHeading(line: String): Boolean =
            HEADING.containsMatchIn(line) && line.trim().startsWith("#")

        private fun heading(): MarkdownBlock.Heading {
            val line = input.next()
            val level = line.trimStart().takeWhile { it == '#' }.length.coerceAtMost(6)
            return MarkdownBlock.Heading(level = level, inlines = InlineScanner.parse(line.trimStart().drop(level).trim()))
        }

        private fun isRule(line: String): Boolean = RULE.matches(line.trim())

        private fun quote(): MarkdownBlock.Quote {
            val inner = mutableListOf<String>()
            while (!input.done()) {
                val line = input.peek()!!
                if (!line.trimStart().startsWith(">")) break
                inner += line.trimStart().removePrefix(">").removePrefix(" ")
                input.next()
            }
            val nested = MarkdownParser.parse(inner.joinToString("\n"))
            return MarkdownBlock.Quote(nested.blocks)
        }

        /** A pipe block is a table only when the second row is a delimiter row. */
        private fun isTableStart(): Boolean {
            val header = input.peek() ?: return false
            val delimiter = input.lines.getOrNull(input.index + 1) ?: return false
            return header.contains('|') && isDelimiterRow(delimiter)
        }

        private fun isDelimiterRow(line: String): Boolean {
            val cells = cells(line)
            return cells.isNotEmpty() && cells.all { DELIMITER_CELL.matches(it.trim()) }
        }

        private fun cells(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|").split('|')

        private fun table(): MarkdownBlock.Table {
            val header = cells(input.next()).map { InlineScanner.parse(it.trim()) }
            val delimiter = cells(input.next()).map { it.trim() }
            val alignments = delimiter.mapNotNull { cell ->
                when {
                    cell.endsWith(":") && cell.startsWith(":") -> Alignment.Center
                    cell.endsWith(":") -> Alignment.Right
                    cell.startsWith(":") -> Alignment.Left
                    else -> Alignment.None
                }
            }
            val rows = mutableListOf<List<List<Inline>>>()
            while (!input.done()) {
                val line = input.peek()!!
                if (line.isBlank() || !line.contains('|')) break
                rows += cells(input.next()).map { InlineScanner.parse(it.trim()) }
            }
            return MarkdownBlock.Table(header = header, alignments = alignments, rows = rows)
        }

        private fun isListItem(line: String): Boolean = LIST_ITEM.matches(line)

        /** One block per list, its items indented by depth: 0 for the gutter, 2 per level. */
        private fun list(): MarkdownBlock.ListBlock {
            val first = LIST_ITEM.find(input.peek()!!)!!
            // Groups: leading indent, the number of an ordered item, the item's own text.
            val ordered = first.groupValues[2].isNotEmpty()
            val start = first.groupValues[2].toIntOrNull() ?: 1
            val items = mutableListOf<ListItem>()
            while (!input.done()) {
                val line = input.peek()!!
                val match = LIST_ITEM.find(line) ?: break
                // A switch between bullets and numbers starts a new block.
                if (match.groupValues[2].isNotEmpty() != ordered) break
                input.next()
                items += ListItem(
                    depth = match.groupValues[1].length / 2,
                    inlines = InlineScanner.parse(match.groupValues[3].trim()),
                )
            }
            return MarkdownBlock.ListBlock(ordered = ordered, items = items, start = start)
        }

        private fun paragraph(): MarkdownBlock.Paragraph {
            val text = mutableListOf<String>()
            while (!input.done()) {
                val line = input.peek()!!
                if (line.isBlank() || isFence(line) || isHeading(line) || isRule(line) ||
                    line.trimStart().startsWith(">") || isListItem(line) || isTableStart()
                ) {
                    break
                }
                text += input.next().trim()
            }
            return MarkdownBlock.Paragraph(InlineScanner.parse(text.joinToString("\n")))
        }
    }

    // ---------------------------------------------------------------- inlines

    private object InlineScanner {

        fun parse(text: String): List<Inline> {
            val out = mutableListOf<Inline>()
            val literal = StringBuilder()
            var index = 0

            fun flush() {
                if (literal.isNotEmpty()) {
                    out += Inline.Text(literal.toString())
                    literal.clear()
                }
            }

            while (index < text.length) {
                val char = text[index]
                when {
                    char == '\\' && index + 1 < text.length -> {
                        literal.append(text[index + 1])
                        index += 2
                    }

                    char == '`' -> {
                        val close = text.indexOf('`', index + 1)
                        if (close > index + 1) {
                            flush()
                            out += Inline.Code(text.substring(index + 1, close))
                            index = close + 1
                        } else {
                            literal.append(char)
                            index += 1
                        }
                    }

                    text.startsWith("**", index) || text.startsWith("__", index) -> {
                        val marker = text.substring(index, index + 2)
                        val close = text.indexOf(marker, index + 2)
                        if (close > index + 2) {
                            flush()
                            out += Inline.Strong(parse(text.substring(index + 2, close)))
                            index = close + 2
                        } else {
                            literal.append(marker)
                            index += 2
                        }
                    }

                    char == '*' || char == '_' -> {
                        val close = text.indexOf(char, index + 1)
                        val inner = if (close > index + 1) text.substring(index + 1, close) else ""
                        // `*` with a space right after it is a bullet or an asterisk, not emphasis.
                        if (inner.isNotEmpty() && !inner.first().isWhitespace()) {
                            flush()
                            out += Inline.Emphasis(parse(inner))
                            index = close + 1
                        } else {
                            literal.append(char)
                            index += 1
                        }
                    }

                    char == '[' -> {
                        val label = text.indexOf(']', index + 1)
                        val destination = if (label > index) text.indexOf('(', label + 1) else -1
                        val close = if (destination > label) text.indexOf(')', destination + 1) else -1
                        if (label > index && destination == label + 1 && close > destination + 1) {
                            flush()
                            out += Inline.Link(
                                inlines = parse(text.substring(index + 1, label)),
                                destination = text.substring(destination + 1, close),
                            )
                            index = close + 1
                        } else {
                            literal.append(char)
                            index += 1
                        }
                    }

                    else -> {
                        literal.append(char)
                        index += 1
                    }
                }
            }
            flush()
            return out
        }
    }

    private val HEADING = Regex("""^\s{0,3}#{1,6}(\s|$)""")
    private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")
    private val LIST_ITEM = Regex("""^(\s*)(?:(\d{1,9})[.)]\s+|[-*+]\s+)(.*)$""")
    private val DELIMITER_CELL = Regex(""":?-{1,}:?""")
}
