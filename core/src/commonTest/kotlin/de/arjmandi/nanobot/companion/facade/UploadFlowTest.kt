package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.transport.AttachmentRejection
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.CoreError
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.transport.Socket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Upload then send (plan.md C6.6): the bytes go to `POST {ready.upload.path}` and the message
 * carries the reference, never the bytes again. A refused or interrupted upload must leave the
 * draft — and its attachment — where they were.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UploadFlowTest {

    private val chat = "websocket:a"

    private fun image(size: Int = 1_024): PickedFile =
        PickedFile(name = "shot.png", mimeType = "image/png", bytes = ByteArray(size) { 7 })

    private fun uploadClient(status: HttpStatusCode = HttpStatusCode.Created): HttpClient = HttpClient(
        MockEngine {
            if (status == HttpStatusCode.Created) {
                respond("""{"reference":"att-1"}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respondError(status)
            }
        },
    )

    private fun TestScope.core(socket: () -> Socket, http: HttpClient): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,
               "capabilities":["webui.core.v1","webui.attachments.binary.v1"]}},
             "limits":{"attachments":{"max_count":4,"max_file_bytes":6291456,"max_total_bytes":25165824}}}
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
        return NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt(
                bootstrap = { BootstrapOutcome.Ready(payload) },
                openSocket = { _ -> socket() },
            ),
            http = http,
        )
    }

    private suspend fun TestScope.connect(socket: FakeSocket, core: NanobotCore) {
        socket.push(READY)
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
        socket.push("""{"event":"attached","chat_id":"$chat"}""")
        runCurrent()
        socket.sent.clear()
    }

    @Test
    fun acceptedReferenceGoesOnMessage() = runTest {
        val socket = FakeSocket()
        val core = core({ socket }, uploadClient())
        val handle = core.chat(chat)
        connect(socket, core)

        val uploaded = assertIs<UploadResult.Uploaded>(handle.upload(image()))
        assertEquals("att-1", uploaded.reference)
        assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "look at this")))
        runCurrent()

        val media = (Json.parseToJsonElement(socket.sent.single()) as JsonObject)["media"]!!.jsonArray
        assertEquals(
            "att-1",
            media.single().jsonObject["reference"]!!.jsonPrimitive.content,
            "the message carries the reference, not the bytes",
        )
    }

    /**
     * Both apps hand the send the references their own cards hold, and `upload()` has already
     * staged the same reference on the session, so the naive envelope names one file twice. The
     * dev gateway answers that with `detail: attachment_rejected, reason: Duplicate attachment
     * reference` and no turn at all — measured against the live gateway, not guessed — so the
     * frame carries each reference once.
     */
    @Test
    fun aReferenceTheComposerAlreadyHoldsIsNotSentTwice() = runTest {
        val socket = FakeSocket()
        val core = core({ socket }, uploadClient())
        val handle = core.chat(chat)
        connect(socket, core)

        handle.upload(image())
        assertIs<SendReceipt.Accepted>(
            handle.send(OutboundDraft(text = "look at this", media = listOf(AttachmentRef(reference = "att-1")))),
        )
        runCurrent()

        val media = (Json.parseToJsonElement(socket.sent.single()) as JsonObject)["media"]!!.jsonArray
        assertEquals(1, media.size, "one file is one reference")
    }

    @Test
    fun failedUploadKeepsDraft() = runTest {
        val socket = FakeSocket()
        val core = core({ socket }, uploadClient(status = HttpStatusCode.InternalServerError))
        val handle = core.chat(chat)
        connect(socket, core)
        handle.setDraft("look at this")

        val failed = assertIs<UploadResult.Failed>(handle.upload(image()))

        assertEquals(500, assertIs<CoreError.ServerError>(failed.error).status)
        assertEquals("look at this", handle.composer.value.draft, "a failed upload does not consume the note")
        assertTrue(socket.sent.isEmpty(), "and nothing is sent on its behalf")
    }

    @Test
    fun aFileOverTheLimitIsRefusedBeforeItIsUploaded() = runTest {
        var posted = 0
        val client = HttpClient(
            MockEngine {
                posted += 1
                respond("""{"reference":"att-1"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val socket = FakeSocket()
        val core = core({ socket }, client)
        val handle = core.chat(chat)
        connect(socket, core)

        val rejected = assertIs<UploadResult.Rejected>(handle.upload(image(size = 7 * 1024 * 1024)))

        assertEquals(AttachmentRejection.FileTooLarge, rejected.reason, "6 MiB is the gateway's own per-file ceiling (C3.7)")
        assertEquals(0, posted, "the bytes never leave the device")
    }

    @Test
    fun connectionCloseBeforeAcceptKeepsTheReference() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        var current: FakeSocket = first
        val core = core({ current }, uploadClient())
        val handle = core.chat(chat)
        connect(first, core)

        assertIs<UploadResult.Uploaded>(handle.upload(image()))
        core.disconnect(forget = false)
        assertEquals(SendReceipt.Rejected(SendRejection.NotConnected), handle.send(OutboundDraft(text = "look")))
        assertEquals("look", handle.composer.value.draft, "the note is still there while the socket is not")

        current = second
        connect(second, core)
        second.sent.clear()

        assertIs<SendReceipt.Accepted>(handle.send(OutboundDraft(text = "look")))
        runCurrent()
        val media = (Json.parseToJsonElement(second.sent.last()) as JsonObject)["media"]!!.jsonArray
        assertEquals(
            "att-1",
            media.single().jsonObject["reference"]!!.jsonPrimitive.content,
            "the upload is not lost with the socket it was made on",
        )
    }

    private companion object {
        const val READY: String =
            """{"event":"ready","chat_id":"websocket:a","client_id":"companion","upload":{"path":"/api/attachments","token":"up-token"}}"""
    }
}
