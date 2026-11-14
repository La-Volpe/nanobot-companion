package de.arjmandi.nanobot.companion.core

import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.chat.ChatThreadView
import de.arjmandi.nanobot.companion.chat.ChatTurnView
import de.arjmandi.nanobot.companion.facade.ChatHandle
import de.arjmandi.nanobot.companion.facade.OutboundDraft
import de.arjmandi.nanobot.companion.facade.SendReceipt
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.Turn
import de.arjmandi.nanobot.companion.projection.TurnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One chat, as the app drives it (A3). Reads the handle's thread and maps it to what Android draws;
 * every command forwards to the facade and nothing branches here — the reducer's decisions are the
 * core's, which is why a replayed history (D46) and a live turn produce the same rows.
 */
class CoreChatDriver(
    private val handle: ChatHandle,
    scope: CoroutineScope,
) : ChatDriver {

    override val thread: StateFlow<ChatThreadView> = handle.thread
        .map { it.toView() }
        .stateIn(scope, SharingStarted.Eagerly, handle.thread.value.toView())

    override suspend fun open(chatId: String?) {
        handle.open()
    }

    override suspend fun send(text: String): String? =
        when (val receipt = handle.send(OutboundDraft(text = text))) {
            is SendReceipt.Accepted -> receipt.turnId
            is SendReceipt.Rejected -> null
        }

    override suspend fun stop() {
        handle.stop()
    }
}

/**
 * `ThreadState` → the value the thread draws. The core keeps the segments, the status and the
 * notices; this only names what a row needs, so a screen never sees a reducer type.
 */
internal fun ThreadState.toView(): ChatThreadView = ChatThreadView(turns = turns.map(Turn::toView))

internal fun Turn.toView(): ChatTurnView {
    val segments = answer.segments
    return ChatTurnView(
        id = id,
        prompt = prompt?.text,
        promptPending = prompt?.optimistic == true,
        answer = segments.joinToString("") { it.text },
        // A turn streams while it runs and its last segment is still open.
        answerStreaming = status == TurnStatus.Running && segments.lastOrNull()?.closed == false,
        running = status == TurnStatus.Running,
    )
}
