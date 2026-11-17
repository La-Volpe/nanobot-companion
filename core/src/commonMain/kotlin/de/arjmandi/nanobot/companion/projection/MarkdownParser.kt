package de.arjmandi.nanobot.companion.projection

/**
 * The parser. Two scanners, because the two grammars are different:
 * a block scanner consumes whole lines and decides what the next line means, and an
 * inline scanner walks one line's worth of text. Both are pure functions of their input,
 * which is what makes the streaming split an ordinary unit test.
 *
 * The subset is the one the WebUI's renderer draws (`MarkdownTextRenderer.tsx`: `remark-gfm`
 * over `streamdown`), so tables, task items, strikethrough and autolinks are in it. What is
 * outside it is never thrown away: an unrecognised line is paragraph text, so a gateway
 * feature this client lacks still reads as prose.
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

        private fun isFence(line: String): Boolean = fenceRun(line) >= 3

        /** The run a fence opens or closes with: at least three of one character, and nothing before it. */
        private fun fenceRun(line: String): Int {
            val trimmed = line.trimStart()
            val char = trimmed.firstOrNull() ?: return 0
            if (char != '`' && char != '~') return 0
            val run = trimmed.takeWhile { it == char }.length
            return if (run >= 3) run else 0
        }

        private fun fence(): MarkdownBlock.CodeFence {
            val opener = input.next().trimStart()
            val char = opener.first()
            val marker = opener.takeWhile { it == char }.length
            // An info string's first word is the language; the rest is attributes
            // (`kotlin title="Thing.kt"`), which this client has nowhere to put.
            val language = opener.drop(marker).trim().substringBefore(' ').ifEmpty { null }
            val body = mutableListOf<String>()
            var closed = false
            while (!input.done()) {
                val line = input.next()
                // CommonMark: a closing fence is the same character, at least as long as its
                // opener, and nothing else on the line. Anything shorter is content, which is
                // what keeps ```` ```` ```` around a ```` ``` ```` one block.
                if (closesFence(line, char, marker)) {
                    closed = true
                    break
                }
                body += line
            }
            return MarkdownBlock.CodeFence(language = language, code = body.joinToString("\n"), closed = closed)
        }

        private fun closesFence(line: String, char: Char, opener: Int): Boolean {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.first() != char) return false
            val run = trimmed.takeWhile { it == char }.length
            return run >= opener && run == trimmed.length
        }

        private fun isHeading(line: String): Boolean =
            HEADING.containsMatchIn(line) && line.trim().startsWith("#")

        private fun heading(): MarkdownBlock.Heading {
            val line = input.next()
            val level = line.trimStart().takeWhile { it == '#' }.length.coerceAtMost(6)
            // `## Title ##` is a heading with a closing run, not a heading called "Title ##".
            val body = line.trimStart().drop(level).trim().replace(CLOSING_HASHES, "")
            return MarkdownBlock.Heading(level = level, inlines = InlineScanner.parse(body))
        }

        private fun isRule(line: String): Boolean = RULE.matches(line.trim())

        private fun quote(): MarkdownBlock.Quote {
            val inner = mutableListOf<String>()
            while (!input.done()) {
                val line = input.peek()!!
                when {
                    line.trimStart().startsWith(">") -> {
                        inner += line.trimStart().removePrefix(">").removePrefix(" ")
                        input.next()
                    }
                    // A blank line between two quoted lines is a paragraph break inside one
                    // quote, not the end of it: `> one`, `>`, `> two` is one block.
                    line.isBlank() && nextNonBlankIsQuoted() -> {
                        inner += ""
                        input.next()
                    }
                    else -> break
                }
            }
            val nested = MarkdownParser.parse(inner.joinToString("\n"))
            return MarkdownBlock.Quote(nested.blocks)
        }

        private fun nextNonBlankIsQuoted(): Boolean {
            var index = input.index
            while (index < input.lines.size) {
                val line = input.lines[index]
                if (line.isNotBlank()) return line.trimStart().startsWith(">")
                index += 1
            }
            return false
        }

        /**
         * A pipe block is a table only when the next row is a delimiter row with a cell per
         * header cell. One cell short is a delimiter row still arriving, and a table that
         * cannot line its columns up is worse than the source text.
         */
        private fun isTableStart(): Boolean {
            val header = input.peek() ?: return false
            val delimiter = input.lines.getOrNull(input.index + 1) ?: return false
            if (!header.contains('|')) return false
            val delimiterCells = cells(delimiter)
            return delimiterCells.isNotEmpty() &&
                delimiterCells.size == cells(header).size &&
                delimiterCells.all { DELIMITER_CELL.matches(it.trim()) }
        }

        /** Split on the pipes that are not escaped: `a \| b` is one cell holding a pipe. */
        private fun cells(line: String): List<String> {
            val trimmed = line.trim()
            val body = trimmed.removePrefix("|").removeSuffix("|")
            val out = mutableListOf<String>()
            val cell = StringBuilder()
            var index = 0
            while (index < body.length) {
                val char = body[index]
                if (char == '\\' && index + 1 < body.length && body[index + 1] == '|') {
                    cell.append("\\|")
                    index += 2
                } else if (char == '|') {
                    out += cell.toString()
                    cell.clear()
                    index += 1
                } else {
                    cell.append(char)
                    index += 1
                }
            }
            out += cell.toString()
            return out
        }

        private fun table(): MarkdownBlock.Table {
            val header = cells(input.next()).map { InlineScanner.parse(it.trim()) }
            val delimiter = cells(input.next()).map { it.trim() }
            val alignments = delimiter.map { cell ->
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
                // GFM lines a table up by the header: a short row is padded and a long one cut,
                // so the grid a platform draws is always the header's width.
                val row = cells(input.next()).take(header.size).map { InlineScanner.parse(it.trim()) }
                rows += row + List(header.size - row.size) { emptyList() }
            }
            return MarkdownBlock.Table(header = header, alignments = alignments, rows = rows)
        }

        private fun isListItem(line: String): Boolean = LIST_ITEM.matches(line)

        /**
         * One block per list. Depth comes from an indent stack rather than from dividing the
         * indent by a unit, because a reply nests at four spaces as often as at two and both
         * mean one level (the matrix's "four spaces is one level").
         */
        private fun list(): MarkdownBlock.ListBlock {
            val first = LIST_ITEM.find(input.peek()!!)!!
            val ordered = first.groupValues[2].isNotEmpty()
            val start = first.groupValues[2].toIntOrNull() ?: 1
            val items = mutableListOf<ListItem>()
            val indents = mutableListOf(0)
            while (!input.done()) {
                val line = input.peek()!!
                val match = LIST_ITEM.find(line) ?: break
                // A switch between bullets and numbers starts a new block.
                if (match.groupValues[2].isNotEmpty() != ordered) break
                val indent = match.groupValues[1].replace("\t", "    ").length
                while (indents.size > 1 && indent < indents.last()) indents.removeAt(indents.lastIndex)
                if (indent > indents.last()) indents += indent
                val depth = indents.lastIndex
                input.next()
                val text = StringBuilder(match.groupValues[3])
                // A wrapped item continues on the lines under it, until a new item, a blank
                // line or a block of another kind. Without this a long bullet's second line
                // becomes a paragraph of its own at depth zero.
                while (!input.done()) {
                    val next = input.peek()!!
                    if (next.isBlank() || isListItem(next) || isFence(next) || isHeading(next) ||
                        isRule(next) || next.trimStart().startsWith(">") || isTableStart()
                    ) {
                        break
                    }
                    text.append('\n').append(input.next().trim())
                }
                val (checked, body) = taskItem(text.toString())
                items += ListItem(depth = depth, inlines = InlineScanner.parse(body.trim()), checked = checked)
            }
            return MarkdownBlock.ListBlock(ordered = ordered, items = items, start = start)
        }

        /** `[ ] open` / `[x] done` is a task item; the box moves to the item and out of its text. */
        private fun taskItem(text: String): Pair<Boolean?, String> = when {
            text.startsWith("[ ] ") -> false to text.drop(4)
            text.startsWith("[x] ") || text.startsWith("[X] ") -> true to text.drop(4)
            else -> null to text
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
                        val run = runLength(text, index, '`')
                        val close = findRun(text, '`', run, index + run)
                        if (close > index) {
                            flush()
                            out += Inline.Code(text.substring(index + run, close))
                            index = close + run
                        } else {
                            literal.append(char)
                            index += 1
                        }
                    }

                    text.startsWith("~~", index) -> {
                        val close = text.indexOf("~~", index + 2)
                        if (close > index + 2) {
                            flush()
                            out += Inline.Strikethrough(parse(text.substring(index + 2, close)))
                            index = close + 2
                        } else {
                            literal.append("~~")
                            index += 2
                        }
                    }

                    char == '*' || char == '_' -> {
                        val run = runLength(text, index, char).coerceAtMost(3)
                        val usable = char != '_' || canUnderscoreOpen(text, index, run)
                        val close = if (usable) findRun(text, char, run, index + run) else -1
                        val inner = if (close > index) text.substring(index + run, close) else ""
                        // A marker with a space right after it is a bullet or an asterisk, not emphasis.
                        if (usable && inner.isNotEmpty() && !inner.first().isWhitespace()) {
                            flush()
                            out += when (run) {
                                3 -> Inline.Strong(listOf(Inline.Emphasis(parse(inner))))
                                2 -> Inline.Strong(parse(inner))
                                else -> Inline.Emphasis(parse(inner))
                            }
                            index = close + run
                        } else {
                            literal.append(char)
                            index += 1
                        }
                    }

                    char == '<' -> {
                        val close = text.indexOf('>', index + 1)
                        val target = if (close > index) text.substring(index + 1, close) else ""
                        if (close > index && isAutolinkTarget(target)) {
                            flush()
                            out += Inline.Link(listOf(Inline.Text(target)), target)
                            index = close + 1
                        } else {
                            literal.append(char)
                            index += 1
                        }
                    }

                    char == '[' -> {
                        val label = closingBracket(text, index)
                        val open = if (label > index) text.indexOf('(', label + 1) else -1
                        val close = if (open == label + 1) closingParen(text, open) else -1
                        if (label > index && close > open) {
                            flush()
                            out += Inline.Link(
                                inlines = parse(text.substring(index + 1, label)),
                                destination = destination(text.substring(open + 1, close)),
                            )
                            index = close + 1
                        } else {
                            literal.append(char)
                            index += 1
                        }
                    }

                    // A bare url is a link in GFM, and a reply that names one is the common case.
                    isBareUrlStart(text, index) -> {
                        val end = bareUrlEnd(text, index)
                        flush()
                        out += Inline.Link(listOf(Inline.Text(text.substring(index, end))), text.substring(index, end))
                        index = end
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

        /** The length of the run of `char` starting at `index`, which for backticks is the fence. */
        private fun runLength(text: String, index: Int, char: Char): Int {
            var end = index
            while (end < text.length && text[end] == char) end += 1
            return end - index
        }

        /**
         * The start of the next run of exactly `run` copies, and not the front of a longer run,
         * so `*italic **bold** inside*` closes where the reader expects and `***both***` opens
         * once.
         */
        private fun findRun(text: String, char: Char, run: Int, from: Int): Int {
            var index = from
            while (index < text.length) {
                if (text[index] == char) {
                    val length = runLength(text, index, char)
                    if (length == run) return index
                    index += length
                } else {
                    index += 1
                }
            }
            return -1
        }

        /** `snake_case_name` is an identifier: `_` inside a word does not open emphasis. */
        private fun canUnderscoreOpen(text: String, index: Int, run: Int): Boolean {
            val before = text.getOrNull(index - 1)?.isLetterOrDigit() == true
            val after = text.getOrNull(index + run)?.isLetterOrDigit() == true
            return !(before && after)
        }

        private fun closingBracket(text: String, open: Int): Int {
            var depth = 0
            var index = open
            while (index < text.length) {
                when (text[index]) {
                    '\\' -> index += 1
                    '[' -> depth += 1
                    ']' -> {
                        depth -= 1
                        if (depth == 0) return index
                    }
                }
                index += 1
            }
            return -1
        }

        private fun closingParen(text: String, open: Int): Int {
            var depth = 0
            var index = open
            while (index < text.length) {
                when (text[index]) {
                    '\\' -> index += 1
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) return index
                    }
                }
                index += 1
            }
            return -1
        }

        /** A destination's title (`[a](url "Title")`) is not part of the url. */
        private fun destination(raw: String): String {
            val trimmed = raw.trim()
            val angle = trimmed.startsWith("<") && trimmed.contains('>')
            if (angle) return trimmed.substringAfter('<').substringBefore('>')
            return trimmed.substringBefore(' ').substringBefore('\t')
        }

        private fun isAutolinkTarget(target: String): Boolean =
            target.startsWith("https://") || target.startsWith("http://") || target.startsWith("mailto:")

        private fun isBareUrlStart(text: String, index: Int): Boolean {
            if (!text.startsWith("https://", index) && !text.startsWith("http://", index)) return false
            val before = text.getOrNull(index - 1)
            // Inside a word (`xhttps://`) or inside a longer url is not a new link.
            return before == null || !(before.isLetterOrDigit() || before == '/' || before == '@')
        }

        private fun bareUrlEnd(text: String, start: Int): Int {
            var end = start
            while (end < text.length && !text[end].isWhitespace() && text[end] != '<' && text[end] != '>') end += 1
            // GFM leaves trailing punctuation out of the link, and a closing bracket that has no
            // opening one in the url.
            while (end > start) {
                val last = text[end - 1]
                val unbalanced = last == ')' && text.substring(start, end).count { it == '(' } <
                    text.substring(start, end).count { it == ')' }
                if (last in TRAILING_PUNCTUATION || unbalanced) end -= 1 else break
            }
            return end
        }
    }

    private val HEADING = Regex("""^\s{0,3}#{1,6}(\s|$)""")
    private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")
    private val LIST_ITEM = Regex("""^(\s*)(?:(\d{1,9})[.)]\s+|[-*+]\s+)(.*)$""")
    private val DELIMITER_CELL = Regex(""":?-{1,}:?""")
    private val CLOSING_HASHES = Regex("""\s+#+\s*$""")
    private const val TRAILING_PUNCTUATION = ".,;:!?\"'"
}
