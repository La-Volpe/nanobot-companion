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
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Voice is server-side: `transcribe_audio {request_id, data_url, duration_ms}` out and
 * `transcription_result {request_id, text}` back (`transcription_ws.py:51`), with 650 ms and
 * 120 s as the gateway's own bounds. Both bounds are refused here, so a held
 * tap that never became audio costs nothing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TranscriptionTest {

    private val chat = "websocket:a"
    private val pcm = ByteArray(64) { 1 }

    private suspend fun TestScope.core(socket: FakeSocket): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}}}
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

    private fun requestIdOf(sent: String): String =
        ((Json.parseToJsonElement(sent) as JsonObject)["request_id"] ?: error("no request_id in $sent")).jsonPrimitive.content

    @Test
    fun holdUnder650msRejectedLocally() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        val result = handle.transcribe(pcm, durationMs = 649)

        assertEquals(TranscriptionResult.Rejected(TranscriptionRejection.TooShort), result)
        assertTrue(socket.sent.isEmpty(), "a tap that short never becomes a request")
    }

    @Test
    fun over120sRejectedLocally() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        val result = handle.transcribe(pcm, durationMs = 120_001)

        assertEquals(TranscriptionResult.Rejected(TranscriptionRejection.TooLong), result)
        assertTrue(socket.sent.isEmpty(), "the gateway caps at 120 s; the request is never sent")
    }

    @Test
    fun resultReplacesDraftTail() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)
        handle.setDraft("note: ")

        val pending = async { handle.transcribe(pcm, durationMs = 1_200) }
        runCurrent()
        val requestId = requestIdOf(socket.sent.single())
        socket.push("""{"event":"transcription_result","request_id":"$requestId","text":"buy milk"}""")
        val result = pending.await()

        assertEquals(TranscriptionResult.Transcribed("buy milk"), result)
        assertEquals("note: buy milk", handle.composer.value.draft, "the transcript lands at the end of what was typed")
    }

    @Test
    fun requestIdIsUnique() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        val first = async { handle.transcribe(pcm, durationMs = 1_000) }
        runCurrent()
        val firstId = requestIdOf(socket.sent.single())
        socket.push("""{"event":"transcription_result","request_id":"$firstId","text":"one"}""")
        first.await()

        val second = async { handle.transcribe(pcm, durationMs = 1_000) }
        runCurrent()
        val secondId = requestIdOf(socket.sent.last())
        socket.push("""{"event":"transcription_result","request_id":"$secondId","text":"two"}""")
        second.await()

        assertTrue(firstId != secondId, "two recordings cannot share a request id")
        assertEquals("onetwo", handle.composer.value.draft, "each transcript appends literally where the last one ended; spacing is the platform's call")
    }

    @Test
    fun timeoutSurfacesError() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        val pending = async { handle.transcribe(pcm, durationMs = 2_000) }
        runCurrent()
        advanceUntilIdle()

        val result = assertIs<TranscriptionResult.Failed>(pending.await())
        assertEquals(TRANSCRIPTION_TIMEOUT, result.detail, "a gateway that never answers is a failure, not a hang")
    }

    @Test
    fun aGatewayRefusalCarriesItsDetail() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        val pending = async { handle.transcribe(pcm, durationMs = 2_000) }
        runCurrent()
        val requestId = requestIdOf(socket.sent.single())
        socket.push("""{"event":"transcription_error","request_id":"$requestId","detail":"transcription_disabled"}""")

        assertEquals(TranscriptionResult.Failed("transcription_disabled"), pending.await())
        assertEquals("", handle.composer.value.draft, "a refusal types nothing")
    }

    private companion object {
        /** The one detail string this client makes up, because the gateway cannot answer at all. */
        const val TRANSCRIPTION_TIMEOUT: String = "transcription_timeout"
    }
}
