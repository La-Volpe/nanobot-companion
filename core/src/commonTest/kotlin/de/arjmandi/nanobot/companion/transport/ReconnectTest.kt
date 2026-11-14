package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.BootstrapPayload
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import de.arjmandi.nanobot.companion.wire.ReadyFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A bootstrap token is consumed by the handshake, which is the failure mode that makes a
 * mobile chat client go quiet after the phone sleeps (plan.md C3.4, §11). One connection
 * attempt is therefore exactly one bootstrap plus one handshake, never a reused token.
 */
class ReconnectTest {

    private inner class Gateway(private val tokens: Iterator<String>, private val payload: (String) -> BootstrapPayload) {
        val bootstrapCalls = mutableListOf<String>()
        val handshakeUrls = mutableListOf<String>()

        val source = BootstrapSource { secret ->
            bootstrapCalls += secret
            BootstrapOutcome.Ready(payload(tokens.next()))
        }

        val socket: (String) -> Socket = { url ->
            handshakeUrls += url
            FiniteSocket(de.arjmandi.nanobot.companion.wire.FrameCodec.decode("""{"event":"ready","chat_id":"chat-1"}"""))
        }
    }

    private fun payload(token: String): BootstrapPayload {
        val text = """
            {"token":"$token","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "terminal":{"webui":{"version":"0.4.1","min_protocol":1,"max_protocol":1,
               "capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        return assertIs<BootstrapResult.Parsed>(de.arjmandi.nanobot.companion.wire.BootstrapParser.parse(text)).payload
    }

    @Test
    fun neverReusesConsumedToken() = runTest {
        val gateway = Gateway(listOf("first-token", "second-token").iterator(), ::payload)
        val attempt = ConnectionAttempt(gateway.source, gateway.socket)

        assertIs<ConnectionResult.Open>(attempt.open("s3cr3t"))
        assertIs<ConnectionResult.Open>(attempt.open("s3cr3t"))

        assertEquals(2, gateway.bootstrapCalls.size, "every attempt bootstraps again")
        assertEquals(
            listOf("ws://127.0.0.1:18765/?client_id=nanobot-companion&token=first-token", "ws://127.0.0.1:18765/?client_id=nanobot-companion&token=second-token"),
            gateway.handshakeUrls,
            "each handshake carries the token minted for it",
        )
    }

    @Test
    fun bootstrapsAgainAfterClose() = runTest {
        val gateway = Gateway(listOf("a", "b").iterator(), ::payload)
        val attempt = ConnectionAttempt(gateway.source, gateway.socket)

        val first = assertIs<ConnectionResult.Open>(attempt.open("s3cr3t"))
        assertIs<ReadyFrame>(first.socket.frames.toList().single())

        // The socket closed. A reconnect is a new attempt, not a resume of the old one.
        val second = assertIs<ConnectionResult.Open>(attempt.open("s3cr3t"))

        assertEquals(2, gateway.bootstrapCalls.size)
        assertIs<ReadyFrame>(second.socket.frames.toList().single())
    }

    @Test
    fun authFailureStopsBeforeTheHandshake() = runTest {
        var handshakes = 0
        val attempt = ConnectionAttempt(
            bootstrap = { BootstrapOutcome.Failed(CoreError.AuthFailed) },
            openSocket = { handshakes++; FakeSocket() },
        )

        val result = attempt.open("wrong")

        assertEquals(CoreError.AuthFailed, assertIs<ConnectionResult.Failed>(result).error)
        assertEquals(0, handshakes, "there is no socket to open when the key is refused")
    }

    @Test
    fun protocolTooNewStopsBeforeTheHandshake() = runTest {
        val attempt = ConnectionAttempt(
            bootstrap = { BootstrapOutcome.Failed(CoreError.ProtocolTooNew(hostMinProtocol = 2, clientProtocol = 1)) },
            openSocket = { FakeSocket() },
        )

        val result = assertIs<ConnectionResult.Failed>(attempt.open("s3cr3t"))

        assertEquals(CoreError.ProtocolTooNew(2, 1), result.error, "a newer host is a clear error, not a broken chat")
    }
}
