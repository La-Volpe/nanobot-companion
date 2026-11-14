package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.JsonObject

/**
 * The answer stream. `delta` and `stream_end` share a stream id; `merge_next` and
 * `resuming` are the gateway's way of saying "this continues the previous segment"
 * (plan.md C4.2), so they are typed here rather than read ad hoc in the reducer.
 */
internal sealed class StreamFrame : Frame {
    abstract val text: String?
    abstract val streamId: String?
    abstract val turnId: String?
    abstract val turnPhase: String?
    abstract val turnSeq: Int?
    abstract val resuming: Boolean
    abstract val mergeNext: Boolean
    abstract val responseSources: List<ResponseSource>
    abstract val responseInputIds: List<String>
}

internal data class DeltaFrame(
    override val text: String?,
    override val streamId: String?,
    override val turnId: String?,
    override val turnPhase: String?,
    override val turnSeq: Int?,
    override val resuming: Boolean,
    override val mergeNext: Boolean,
    override val responseSources: List<ResponseSource>,
    override val responseInputIds: List<String>,
    override val chatId: String?,
    override val raw: JsonObject,
) : StreamFrame() {
    override val event: String get() = "delta"
}

internal data class StreamEndFrame(
    override val text: String?,
    override val streamId: String?,
    override val turnId: String?,
    override val turnPhase: String?,
    override val turnSeq: Int?,
    val textFromSeq: Int?,
    override val resuming: Boolean,
    override val mergeNext: Boolean,
    override val responseSources: List<ResponseSource>,
    override val responseInputIds: List<String>,
    override val chatId: String?,
    override val raw: JsonObject,
) : StreamFrame() {
    override val event: String get() = "stream_end"
}

internal data class ReasoningDeltaFrame(
    override val text: String?,
    override val streamId: String?,
    override val turnId: String?,
    override val turnPhase: String?,
    override val turnSeq: Int?,
    override val resuming: Boolean,
    override val mergeNext: Boolean,
    override val responseSources: List<ResponseSource>,
    override val responseInputIds: List<String>,
    override val chatId: String?,
    override val raw: JsonObject,
) : StreamFrame() {
    override val event: String get() = "reasoning_delta"
}

internal data class ReasoningEndFrame(
    override val text: String?,
    override val streamId: String?,
    override val turnId: String?,
    override val turnPhase: String?,
    override val turnSeq: Int?,
    override val resuming: Boolean,
    override val mergeNext: Boolean,
    override val responseSources: List<ResponseSource>,
    override val responseInputIds: List<String>,
    override val chatId: String?,
    override val raw: JsonObject,
) : StreamFrame() {
    override val event: String get() = "reasoning_end"
}

/** Which provider actually answered, and whether it was the configured preset (C6.2 fallback notice). */
internal data class ResponseSource(
    val provider: String?,
    val model: String?,
    val preset: String?,
    val fallback: Boolean,
)
