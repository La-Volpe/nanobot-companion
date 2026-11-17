package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.Outbound

/**
 * Stopping, steering and the prompt queue. All three answer the same question, what may the
 * client send while a turn runs, so they live in one pure state machine.
 *
 * Stop is the text `/stop` on the chat (there is no stop frame); steering is another
 * `message` that reuses the running turn's id; the queue is client-side because the
 * gateway has nowhere to hold a prompt for later.
 */
internal data class QueuedPrompt(val chatId: String, val text: String)

internal data class TurnControlState(
    val queued: List<QueuedPrompt> = emptyList(),
    /** The turn a `/stop` was already sent for, so a second tap is not a second frame. */
    val stopRequestedFor: String? = null,
)

internal data class ControlReduction(
    val state: TurnControlState,
    val effects: List<Effect> = emptyList(),
)

internal object TurnControl {

    /** The gateway's own ceiling for a client-side queue; the design only ever shows one. */
    const val MAX_QUEUE: Int = 20

    fun stop(state: TurnControlState, chatId: String, activeTurnId: String): ControlReduction =
        if (state.stopRequestedFor == activeTurnId) {
            // The stop is already in flight; a second one would be a second frame for nothing.
            ControlReduction(state)
        } else {
            ControlReduction(
                state = state.copy(stopRequestedFor = activeTurnId),
                effects = listOf(Effect.Send(slashStop(chatId, activeTurnId))),
            )
        }

    fun steer(state: TurnControlState, chatId: String, activeTurnId: String, text: String): ControlReduction =
        ControlReduction(
            state = state,
            effects = listOf(Effect.Send(Outbound.Message(chatId = chatId, turnId = activeTurnId, content = text))),
        )

    fun enqueue(state: TurnControlState, chatId: String, text: String, capacity: Int = MAX_QUEUE): ControlReduction =
        if (state.queued.size >= capacity) {
            ControlReduction(state)
        } else {
            ControlReduction(state.copy(queued = state.queued + QueuedPrompt(chatId = chatId, text = text)))
        }

    /** A settled turn releases the next queued prompt for that chat, in send order. */
    fun onTurnEnd(state: TurnControlState, chatId: String, turnId: String): ControlReduction {
        val released = state.copy(stopRequestedFor = state.stopRequestedFor?.takeUnless { it == turnId })
        val next = released.queued.firstOrNull { it.chatId == chatId } ?: return ControlReduction(released)
        return ControlReduction(
            state = released.copy(queued = released.queued - next),
            effects = listOf(
                Effect.Send(Outbound.Message(chatId = next.chatId, turnId = "queued-${released.queued.size}", content = next.text)),
            ),
        )
    }

    private fun slashStop(chatId: String, turnId: String): Outbound.Message =
        Outbound.Message(chatId = chatId, turnId = turnId, content = "/stop")
}
