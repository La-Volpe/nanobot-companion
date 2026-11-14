package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.AttachedFrame
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.Outbound

/**
 * Many chats, one socket (plan.md C5.4). Every frame carries the chat it belongs to, so
 * routing is a map lookup rather than a decision; what needs deciding is the order around
 * an attach, because a message must not overtake the `attached` that makes its chat real.
 */
internal data class ChatSet(
    val threads: Map<String, ThreadState> = emptyMap(),
    /** Chats the gateway has confirmed with `attached`. */
    val attached: Set<String> = emptySet(),
    /** Frames waiting for their chat's attach, in the order they were sent. */
    val pending: Map<String, List<Outbound>> = emptyMap(),
) {
    fun thread(chatId: String): ThreadState = threads[chatId] ?: ThreadState(chatId = chatId)
}

internal data class ChatRouted(
    val set: ChatSet,
    val effects: List<Effect> = emptyList(),
    /** A frame that names no chat cannot be routed to one; the engine sees it and decides. */
    val unrouted: Frame? = null,
)

internal object ChatRouter {

    /**
     * Send on a chat. An attached chat takes the message directly; any other chat gets the
     * attach that makes it real first, and the message waits in [ChatSet.pending] until the
     * gateway answers. Sending the message first would race the attach and lose the turn.
     */
    fun send(set: ChatSet, chatId: String, message: Outbound.Message): ChatRouted {
        if (chatId in set.attached) {
            return ChatRouted(set, effects = listOf(Effect.Send(message)))
        }
        val queued = set.pending[chatId].orEmpty()
        // One attach per chat in flight: a second send joins the queue rather than asking twice.
        val effects = if (queued.isEmpty()) listOf(Effect.Send(Outbound.Attach(chatId = chatId))) else emptyList()
        return ChatRouted(
            set = set.copy(pending = set.pending + (chatId to queued + message)),
            effects = effects,
        )
    }

    fun reduce(set: ChatSet, frame: Frame, nowMs: Long): ChatRouted = when {
        frame is AttachedFrame && !frame.chatId.isNullOrBlank() -> {
            val chatId: String = frame.chatId
            val waiting = set.pending[chatId].orEmpty()
            ChatRouted(
                set = set.copy(
                    attached = set.attached + chatId,
                    pending = set.pending - chatId,
                    threads = set.threads + (chatId to set.thread(chatId)),
                ),
                effects = waiting.map(Effect::Send),
            )
        }

        frame.chatId.isNullOrBlank() -> ChatRouted(set, unrouted = frame)

        else -> {
            val chatId: String = frame.chatId ?: return ChatRouted(set, unrouted = frame)
            val reduction = ThreadReducer.reduce(set.thread(chatId), frame, nowMs)
            ChatRouted(
                set = set.copy(threads = set.threads + (chatId to reduction.state)),
                // A thread's own effects stay with the thread whose frame produced them.
                effects = reduction.effects,
            )
        }
    }
}
