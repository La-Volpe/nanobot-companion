package de.arjmandi.nanobot.companion.wire

import de.arjmandi.nanobot.companion.fixture.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The codec is written *from* the recorded fixtures: every frame
 * the live gateway sent must decode into a typed frame and re-encode to the same bytes,
 * because the reducer is only as good as what survives this step.
 */
class FrameCodecTest {

    @Test
    fun decodesEveryFixtureWithoutLoss() {
        val frames = Fixtures.wsFrames()
        assertTrue(frames.isNotEmpty(), "no recorded frames; run :core-cli record")

        for (raw in frames) {
            val json = Json.parseToJsonElement(raw.toString())
            val frame = FrameCodec.decode(raw.toString())

            assertEquals(raw["event"].toString().trim('"'), frame.event, "the event name survives decoding")
            assertIsNotUnknown(frame)
            assertEquals(json, frame.raw, "${frame.event}: the raw payload must not be rewritten")
            assertEquals(json, Json.parseToJsonElement(FrameCodec.encode(frame)), "${frame.event}: re-encoding is lossless")
        }
    }

    @Test
    fun typedFieldsMatchTheFixturePayload() {
        val deltas = Fixtures.wsFrames().map { FrameCodec.decode(it.toString()) }.filterIsInstance<DeltaFrame>()
        assertTrue(deltas.isNotEmpty(), "the recording has no delta; the streaming reducer would be written blind")

        val delta = deltas.first()
        assertEquals("pong", delta.text, "delta.text is the streamed chunk")
        assertNotNull(delta.streamId, "stream_id is opaque but present")
        assertEquals("turn-1", delta.turnId)
        assertEquals("answer", delta.turnPhase)
        assertEquals(3, delta.turnSeq)
        assertEquals("ollama", delta.responseSources.single().provider)
        assertEquals(listOf("user:turn-1"), delta.responseInputIds)

        val end = Fixtures.wsFrames().map { FrameCodec.decode(it.toString()) }.filterIsInstance<TurnEndFrame>().single()
        assertEquals("turn-1", end.turnId)
        assertEquals(5703, end.usage?.promptTokens, "turn_end carries usage")
        assertEquals(32768, end.contextWindowTokens)
        assertEquals(false, end.goalState.active)
    }

    @Test
    fun readyCarriesTheUploadCapability() {
        val ready = FrameCodec.decode(Fixtures.wsFrames().first().toString())

        assertIs<ReadyFrame>(ready)
        assertEquals("UUID-2", ready.chatId)
        assertEquals("/api/attachments", ready.upload?.path, "ready advertises the upload path")
        assertEquals("REDACTED", ready.upload?.token, "the upload token is a live capability and must be redacted")
    }

    @Test
    fun unknownEventDecodesToUnknownNotError() {
        val future = """{"event":"telemetry_ping","chat_id":"UUID-2","payload":{"nested":[1,2,3]}}"""

        val frame = FrameCodec.decode(future)

        assertIs<UnknownFrame>(frame, "a frame from a newer gateway must not throw")
        assertEquals("telemetry_ping", frame.event)
        assertEquals("UUID-2", frame.chatId, "even an unknown frame knows its chat, so it can be routed")
    }

    @Test
    fun unknownFieldsSurviveRoundTrip() {
        val recorded = Fixtures.wsFrames().first { it.event() == "turn_end" }
        val withExtra = buildJsonObject {
            recorded.forEach { (key, value) -> put(key, value) }
            put("brand_new_field", "kept")
        }

        val frame = FrameCodec.decode(withExtra.toString())

        assertEquals(
            Json.parseToJsonElement(withExtra.toString()),
            Json.parseToJsonElement(FrameCodec.encode(frame)),
            "a field this client does not know yet must come back out unchanged",
        )
    }

    @Test
    fun misShapedFieldsDegradeInsteadOfThrowing() {
        // The event name is the routing contract; a field with the wrong type degrades to
        // null while the raw payload is kept, so the reducer sees a delta it can show.
        val misShaped = FrameCodec.decode("""{"event":"delta","chat_id":42,"text":7}""")

        assertIs<DeltaFrame>(misShaped)
        assertNull(misShaped.chatId, "a non-string chat_id is not a chat id")
        assertNull(misShaped.text, "a non-string text is not a chunk")
        assertEquals("""{"event":"delta","chat_id":42,"text":7}""", FrameCodec.encode(misShaped), "the payload is untouched")

        val notJson = FrameCodec.decode("<html>proxy error</html>")
        assertIs<UnknownFrame>(notJson, "unparseable input is unknown, not a crash")
    }

    private fun assertIsNotUnknown(frame: Frame) {
        assertTrue(frame !is UnknownFrame, "${frame.event}: the codec should know this frame")
    }

    private fun JsonObject.event(): String = (this["event"] as kotlinx.serialization.json.JsonPrimitive).content
}
