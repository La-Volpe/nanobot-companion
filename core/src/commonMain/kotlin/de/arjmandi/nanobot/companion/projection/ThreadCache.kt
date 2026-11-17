package de.arjmandi.nanobot.companion.projection

/**
 * The thread as it survives a restart: drafts and cache are plain JSON in the app's own
 * container, and the token never goes near them.
 *
 * It encodes only. Restore needs a page of history to reconcile against, and that lands with
 * the earlier-page work; a decoder written before its reader would be a shape nobody has to
 * keep.
 */
internal object ThreadCache {

    fun encode(state: ThreadState): String = buildString {
        append("{\"chat_id\":").append(quote(state.chatId))
        append(",\"active_turn_id\":").append(state.activeTurnId?.let(::quote) ?: "null")
        append(",\"turns\":[")
        state.turns.forEachIndexed { index, turn ->
            if (index > 0) append(',')
            append("{\"id\":").append(quote(turn.id))
            append(",\"status\":").append(quote(turn.status.name))
            append(",\"prompt\":").append(turn.prompt?.let { quote(it.text) } ?: "null")
            append(",\"answer\":").append(quote(turn.answer.text))
            append(",\"segments\":[")
            turn.answer.segments.forEachIndexed { segmentIndex, segment ->
                if (segmentIndex > 0) append(',')
                append("{\"id\":").append(quote(segment.id))
                append(",\"text\":").append(quote(segment.text))
                append(",\"closed\":").append(segment.closed)
                append('}')
            }
            append(']')
            append('}')
        }
        append("]}")
    }

    /** JSON string escaping, small enough to own: quotes, backslashes and control characters. */
    private fun quote(text: String): String = buildString {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u").append(char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        append('"')
    }
}
