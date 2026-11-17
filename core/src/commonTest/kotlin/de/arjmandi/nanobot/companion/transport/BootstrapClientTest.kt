package de.arjmandi.nanobot.companion.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * The bootstrap call is the first thing that can fail, and each failure
 * has to arrive as a taxonomy entry, because the eight entries are what the onboarding
 * screen shows instead of an exception string.
 */
class BootstrapClientTest {

    private val payload = """
        {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
         "model_name":"deepseek-v4.1-flash:cloud","api_token":"api",
         "terminal":{"protocolVersion":1,"gatewayId":"g","webui":{"version":"0.4.1",
           "min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}},
         "limits":{"transport":{"max_frame_bytes":37748736,"envelope_reserve_bytes":65536},
           "message":{"max_text_bytes":65536},
           "attachments":{"max_count":4,"max_file_bytes":6291456,"max_total_bytes":25165824}},
         "runtime_capabilities":{"can_restart_engine":false,"can_open_logs":false,"can_export_diagnostics":false}}
    """.trimIndent()

    private fun client(engine: MockEngine): BootstrapClient =
        BootstrapClient(HttpClient(engine), baseUrl = "http://127.0.0.1:18765")

    @Test
    fun sendsSecretHeader() = runTest {
        var seen: String? = null
        var path: String? = null
        val engine = MockEngine { request ->
            seen = request.headers["X-Nanobot-Auth"]
            path = request.url.encodedPath
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val outcome = client(engine).fetch(secret = "s3cr3t")

        assertIs<BootstrapOutcome.Ready>(outcome)
        assertEquals("s3cr3t", seen, "the key travels in X-Nanobot-Auth")
        assertEquals(null, engine.requestHistory.single().headers[HttpHeaders.Authorization], "not a bearer header")
        assertEquals("/webui/bootstrap", path)
    }

    @Test
    fun maps401ToAuthFailed() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }

        val outcome = client(engine).fetch("wrong")

        assertEquals(CoreError.AuthFailed, assertIs<BootstrapOutcome.Failed>(outcome).error)
    }

    @Test
    fun maps403ToAuthFailedToo() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Forbidden) }

        val outcome = client(engine).fetch("nope")

        assertEquals(CoreError.AuthFailed, assertIs<BootstrapOutcome.Failed>(outcome).error)
    }

    @Test
    fun mapsSelfSignedToTlsRejected() = runTest {
        // JVM-style wording; the classifier is shared, so it must read the message.
        val engine = MockEngine { throw IllegalStateException("javax.net.ssl.SSLHandshakeException: PKIX path building failed") }

        val outcome = client(engine).fetch("s3cr3t")

        assertEquals(CoreError.TlsRejected, assertIs<BootstrapOutcome.Failed>(outcome).error)
    }

    @Test
    fun mapsDarwinCertificateFailureToTlsRejected() = runTest {
        val engine = MockEngine {
            throw IllegalStateException("The certificate for this server is invalid. You might be connecting to a server that is pretending to be \"chat.example.com\"")
        }

        val outcome = client(engine).fetch("s3cr3t")

        assertEquals(CoreError.TlsRejected, assertIs<BootstrapOutcome.Failed>(outcome).error, "the Darwin engine words it differently")
    }

    @Test
    fun mapsRefusedConnectionToUnreachable() = runTest {
        val engine = MockEngine { throw IllegalStateException("java.net.ConnectException: Connection refused") }

        val outcome = client(engine).fetch("s3cr3t")

        assertEquals(CoreError.Unreachable, assertIs<BootstrapOutcome.Failed>(outcome).error)
    }

    @Test
    fun mapsTimeoutToTimeout() = runTest {
        val engine = MockEngine { throw HttpRequestTimeoutException("http://127.0.0.1:18765", 5_000L) }

        val outcome = client(engine).fetch("s3cr3t")

        assertEquals(CoreError.Timeout, assertIs<BootstrapOutcome.Failed>(outcome).error)
    }

    @Test
    fun maps500ToServerError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }

        val outcome = client(engine).fetch("s3cr3t")

        assertEquals(CoreError.ServerError(status = 500), assertIs<BootstrapOutcome.Failed>(outcome).error)
    }

    @Test
    fun mapsMalformedBodyToProtocolError() = runTest {
        val engine = MockEngine {
            respond("""{"token":"tok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val outcome = client(engine).fetch("s3cr3t")

        assertIs<CoreError.MissingField>(assertIs<BootstrapOutcome.Failed>(outcome).error)
    }
}
