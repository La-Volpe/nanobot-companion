package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The composer's send path (plan.md C6.1): the bubble appears before the gateway answers, a
 * refused send keeps what was typed, and a frame the gateway would refuse is refused here
 * instead — `ready.limits` is the ceiling, not a suggestion.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SendTest {

    private val chat = "websocket:a"

    private suspend fun TestScope.core(socket: FakeSocket, maxTextBytes: Long = 65_536): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}},
             "limits":{"transport":{"max_frame_bytes":37748736},"message":{"max_text_bytes":$maxTextBytes}}}
        """.trimIndent()
        val payload = assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
        val deps = CoreDeps(
            clock = FakeClock(startMs = 1_000),
            secureStore = InMemorySecureStore(),
            files = InMemoryFileStore(),
            appInfo = FakeAppInfo(),
            dispatchers = CoreDispatchers(
                compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                main = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            ),
        )
        val core = NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(payload) }, { socket }),
            http = null,
        )
        socket.push("""{"event":"ready","chat_id":"$chat","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
        socket.push("""{"event":"attached","chat_id":"$chat"}""")
        runCurrent()
        socket.sent.clear()
        return core
    }

    @Test
    fun optimisticBubbleBeforeAccept() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        val receipt = assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "hello")))
        val optimistic = handle.thread.value.turn(receipt.turnId)?.prompt

        assertEquals("hello", optimistic?.text, "the bubble is on screen before the gateway has said anything")
        assertEquals(true, optimistic?.optimistic, "and it is marked optimistic until message_accepted")

        socket.push("""{"event":"message_accepted","chat_id":"$chat","turn_id":"${receipt.turnId}","active_turn_id":"${receipt.turnId}"}""")
        runCurrent()

        assertEquals(false, handle.thread.value.turn(receipt.turnId)?.prompt?.optimistic, "the ack makes the bubble real")
    }

    @Test
    fun rejectedSendKeepsDraft() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        core.disconnect(forget = false)
        val handle = core.chat(chat)

        val receipt = handle.send(OutboundDraft(text = "do not lose this"))

        assertEquals(SendReceipt.Rejected(SendRejection.NotConnected), receipt)
        assertEquals("do not lose this", handle.composer.value.draft, "a refused send gives the text back, not away")
        assertTrue(handle.thread.value.turns.isEmpty(), "a send that never reached the socket leaves no bubble")
    }

    @Test
    fun frameTooLargeIsRejectedLocally() = runTest {
        val socket = FakeSocket()
        val core = core(socket, maxTextBytes = 10)
        val handle = core.chat(chat)

        val receipt = handle.send(OutboundDraft(text = "this prompt is longer than ten bytes"))

        assertEquals(SendReceipt.Rejected(SendRejection.FrameTooLarge), receipt)
        assertTrue(socket.sent.isEmpty(), "the frame the gateway would reject is never sent")
        assertEquals("this prompt is longer than ten bytes", handle.composer.value.draft, "the draft survives the refusal")
        assertTrue(handle.thread.value.turns.isEmpty())
    }

    @Test
    fun acceptedSendClearsTheDraft() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "sent")))

        assertEquals("", handle.composer.value.draft, "an accepted prompt is no longer a draft")
        runCurrent()
        assertEquals("message", envelopeType(socket.sent.single()))
        assertTrue(socket.sent.single().contains("\"content\":\"sent\""))
        assertNull(handle.thread.value.turn("companion-1000-0")?.answer?.text?.takeIf { it.isNotEmpty() })
    }

    private fun envelopeType(text: String): String =
        (Json.parseToJsonElement(text) as JsonObject)["type"]!!.jsonPrimitive.content
}
