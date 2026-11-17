package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.AttachedFrame
import de.arjmandi.nanobot.companion.wire.ErrorFrame
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.Outbound

/**
 * Opening a chat. A chat exists on the gateway before the client knows its
 * id, so "open" is a request and `attached` is the answer: an existing chat is attached by
 * the id the caller has, a new one takes the id the gateway mints.
 *
 * This is a pure state machine. The frame it sends leaves as [Effect.Send] and the engine
 * puts it on the socket, which is why a test can drive a whole attach without a gateway.
 */
internal data class ChatLifecycleState(
    val chatId: String? = null,
    val phase: Phase = Phase.NotOpen,
    val failure: String? = null,
) {
    internal enum class Phase { NotOpen, Attaching, Attached }
}

internal data class LifecycleReduction(
    val state: ChatLifecycleState,
    val effects: List<Effect> = emptyList(),
)

internal object ChatLifecycle {

    /** Open a chat by id: the gateway confirms with `attached`. */
    fun open(chatId: String): LifecycleReduction = LifecycleReduction(
        state = ChatLifecycleState(chatId = chatId, phase = ChatLifecycleState.Phase.Attaching),
        effects = listOf(Effect.Send(Outbound.Attach(chatId = chatId))),
    )

    /** A new chat has no id until the gateway answers, so the caller must not invent one. */
    fun openNew(): LifecycleReduction = LifecycleReduction(
        state = ChatLifecycleState(phase = ChatLifecycleState.Phase.Attaching),
        effects = listOf(Effect.Send(Outbound.NewChat)),
    )

    fun reduce(state: ChatLifecycleState, frame: Frame): LifecycleReduction = when (frame) {
        is AttachedFrame -> when {
            // `attached` with no chat id is not an attach this client can use.
            frame.chatId.isNullOrBlank() -> LifecycleReduction(
                state.copy(phase = ChatLifecycleState.Phase.NotOpen, failure = "attached_without_chat_id"),
            )

            else -> LifecycleReduction(
                state.copy(chatId = frame.chatId, phase = ChatLifecycleState.Phase.Attached, failure = null),
            )
        }

        // Only a rejection *while attaching* is about the chat being open; a later one is
        // about a message, and closing an attached chat on it would be a lie.
        is ErrorFrame -> if (state.phase == ChatLifecycleState.Phase.Attaching) {
            LifecycleReduction(state.copy(phase = ChatLifecycleState.Phase.NotOpen, failure = frame.detail ?: "error"))
        } else {
            LifecycleReduction(state)
        }

        else -> LifecycleReduction(state)
    }
}
