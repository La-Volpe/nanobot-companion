package de.arjmandi.nanobot.companion.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.arjmandi.nanobot.companion.R
import kotlinx.coroutines.flow.StateFlow

/**
 * The chat screen's state and its two rules (plan.md A3, the Android twin of iOS's
 * `ChatViewModel`): the optimistic bubble is on screen before the gateway answers and is marked
 * pending until it does, and the primary button is Stop only while a turn is running and there is
 * nothing new to send — the WebUI's own rule, `showStopButton = isStreaming && !canSend`.
 *
 * The thread itself is the core's. This reads the driver's flow rather than copying it, so there is
 * one thread in the app and no second place for it to drift (plan.md §10, "no hidden state").
 */
class ChatViewModel(private val chat: ChatDriver) {

    val thread: StateFlow<ChatThreadView> = chat.thread

    var draft by mutableStateOf("")

    /** The sentence a refused send shows, as a resource id (A8 turns it into a snackbar). */
    var lastRefusalRes by mutableStateOf<Int?>(null)
        private set

    val turns: List<ChatTurnView> get() = thread.value.turns

    val isStreaming: Boolean get() = turns.any { it.running }

    /** Typed text is what makes the button Send again: pressing it queues a steer, it does not stop the turn. */
    val canSend: Boolean get() = draft.isNotBlank()

    val showsStop: Boolean get() = isStreaming && !canSend

    val isEmpty: Boolean get() = turns.isEmpty()

    /** The drawer's row, or the greeting's chat when nothing is picked: the core replays the history (D46). */
    suspend fun open(chatId: String?) {
        lastRefusalRes = null
        chat.open(chatId)
    }

    /**
     * The optimistic bubble is not invented here: the core's reducer adds it, and this only
     * forwards what the send produced (D9 — one reducer, two feeds).
     */
    suspend fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        if (isStreaming && !canSend) return
        draft = ""
        lastRefusalRes = null
        if (chat.send(text) == null) {
            // A refused send gives the text back rather than losing it (C6.1).
            draft = text
            lastRefusalRes = R.string.chat_send_refused
        }
    }

    suspend fun stop() {
        chat.stop()
    }

    /** What the primary button does: stop while streaming with an empty field, send otherwise. */
    suspend fun primaryAction() {
        if (showsStop) stop() else send()
    }

    fun clearRefusal() {
        lastRefusalRes = null
    }
}
