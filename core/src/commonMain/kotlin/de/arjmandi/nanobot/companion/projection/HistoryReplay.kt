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
}
