package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The history page turned into the thread a user sees (plan.md D9, C4.6). `GET
 * /api/sessions/{key}/webui-thread` replays the same event vocabulary the socket speaks, so
 * this runs the *same* [ThreadReducer] over the stored events — one reducer, two feeds, and
 * a reload can never disagree with what was watched arrive.
 *
 * Public because both consumers need it: the apps on open, and `core-cli watch --verify`,
 * which diffs this against the live transcript (G1).
 */
public object HistoryReplay {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** [chatId] comes from the caller: the page's events do not all carry one. */
    public fun fromPage(page: String, chatId: String, nowMs: Long = 0): ThreadState =
        fromEvents(runCatching { json.parseToJsonElement(page) as? JsonObject }.getOrNull(), chatId, nowMs)

    public fun fromEvents(page: JsonObject?, chatId: String, nowMs: Long = 0): ThreadState {
        val events = page?.get("events") as? JsonArray ?: JsonArray(emptyList())
        return events.fold(ThreadState(chatId = chatId)) { state, event ->
            ThreadReducer.reduce(state, FrameCodec.decode(event.toString()), nowMs).state
        }
    }

    /**
     * The stored page under whatever the socket already showed (D46). Opening a chat reads the
     * page while the attach is on the wire, so a running turn can arrive first: its live copy is
     * the newer one (the page holds it only up to the last settle), and the stored copy of a turn
     * the socket already has must not appear a second time.
     *
     * Turn order is the page's, then the turns only the socket has; every other field prefers
     * the live value when it has one, because the socket is the newer feed for those too.
     */
    public fun merge(stored: ThreadState, live: ThreadState): ThreadState {
        if (stored.turns.isEmpty() && stored.notices.isEmpty()) return live
        val liveIds = live.turns.mapTo(mutableSetOf()) { it.id }
        val turns = stored.turns.filterNot { it.id in liveIds } + live.turns
        return stored.copy(
            chatId = live.chatId,
            turns = turns,
            activeTurnId = live.activeTurnId ?: stored.activeTurnId,
            notices = live.notices.ifEmpty { stored.notices },
            usage = live.usage ?: stored.usage,
            contextWindowTokens = live.contextWindowTokens ?: stored.contextWindowTokens,
            modelPreset = live.modelPreset ?: stored.modelPreset,
            modelName = live.modelName ?: stored.modelName,
            subagents = live.subagents.ifEmpty { stored.subagents },
        )
    }
}
