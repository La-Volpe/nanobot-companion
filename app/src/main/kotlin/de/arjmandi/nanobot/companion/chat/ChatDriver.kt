package de.arjmandi.nanobot.companion.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One turn as the thread draws it: the prompt above, the answer below, and whether it still runs. */
data class ChatTurnView(
    val id: String,
    val prompt: String?,
    val promptPending: Boolean,
    val answer: String,
    val answerStreaming: Boolean,
    val running: Boolean,
)

/** What the thread draws (A3; A4 adds the activity rows on top). */
data class ChatThreadView(val turns: List<ChatTurnView> = emptyList())

/**
 * One chat as the screen drives it (plan.md A3). The core owns the thread and the reducer; this is
 * the view of it a view model can hold without a socket, which is what lets the streaming, the
 * button rule and the optimistic bubble be tested off-device.
 */
interface ChatDriver {
    val thread: StateFlow<ChatThreadView>

    /** Open the chat: the core attaches and replays the stored history (D46). */
    suspend fun open(chatId: String?)

    /** Returns the turn id the core accepted, or null when the send was refused. */
    suspend fun send(text: String): String?

    suspend fun stop()
}

/** A driver with no socket behind it: the screen's initial state (used until a chat is opened). */
class EmptyChatDriver : ChatDriver {
    private val empty = MutableStateFlow(ChatThreadView())

    override val thread: StateFlow<ChatThreadView> = empty.asStateFlow()

    override suspend fun open(chatId: String?) = Unit

    override suspend fun send(text: String): String? = null

    override suspend fun stop() = Unit
}
