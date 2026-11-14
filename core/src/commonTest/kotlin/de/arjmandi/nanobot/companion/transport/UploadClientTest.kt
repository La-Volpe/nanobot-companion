package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.UploadCapability
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Uploads are the one HTTP write the gateway allows (plan.md C3.6). The capability token
 * from `ready` authorizes exactly this connection, and the gateway refuses a chunked body,
 * so the request has to carry an exact length.
 */
class UploadClientTest {

    private val file = CandidateFile(name = "report.pdf", mimeType = "application/pdf", bytes = 12)
    private val capability = UploadCapability(path = "/api/attachments", token = "upload-token")

    private fun client(engine: MockEngine) = UploadClient(HttpClient(engine), baseUrl = "http://127.0.0.1:18765")

    @Test
    fun setsBearerAndContentLength() = runTest {
        var authorization: String? = null
        var method: HttpMethod? = null
        var contentLength: String? = null
        var transferEncoding: String? = null
        var contentType: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            method = request.method
            contentLength = request.body.contentLength?.toString()
            transferEncoding = request.headers[HttpHeaders.TransferEncoding]
            contentType = request.body.contentType?.toString()
            respond("""{"reference":"ref-1"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        client(engine).upload(capability, file, "hello world!".encodeToByteArray())

        assertEquals("Bearer upload-token", authorization, "the upload token is a bearer capability (D15.5)")
        assertEquals(HttpMethod.Post, method)
        assertEquals("12", contentLength, "the gateway rejects a chunked body, so the length is exact")
        assertEquals(null, transferEncoding)
        assertEquals("application/pdf", contentType)
    }

    @Test
    fun maps201ToReference() = runTest {
        val engine = MockEngine {
            respond("""{"reference":"ref-1"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val result = client(engine).upload(capability, file, ByteArray(12))

        assertEquals("ref-1", assertIs<UploadOutcome.Uploaded>(result).reference, "the message carries the reference (C6.6)")
    }

    @Test
    fun rejectsUnexpectedStatusWithTheTaxonomy() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.PayloadTooLarge) }

        val result = client(engine).upload(capability, file, ByteArray(12))

        assertEquals(CoreError.ServerError(status = 413), assertIs<UploadOutcome.Failed>(result).error)
    }

    @Test
    fun maps401ToAuthFailed() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }

        val result = client(engine).upload(capability, file, ByteArray(12))

        assertEquals(CoreError.AuthFailed, assertIs<UploadOutcome.Failed>(result).error, "an expired capability is an auth failure")
    }
}
