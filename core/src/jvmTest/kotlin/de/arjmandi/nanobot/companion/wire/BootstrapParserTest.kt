package de.arjmandi.nanobot.companion.wire

import de.arjmandi.nanobot.companion.fixture.Fixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /webui/bootstrap` is the only handshake the client has (plan.md C2.4), and the
 * recorded payload is the reference: D15.1 says the protocol contract lives at
 * `terminal.webui` and `runtime_capabilities` is a different thing entirely.
 */
class BootstrapParserTest {

    private fun recorded(): BootstrapPayload {
        val text = File(Fixtures.latest, "bootstrap.json").readText()
        return assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
    }

    @Test
    fun parsesReadyShape() {
        val payload = recorded()

        assertEquals("ws://127.0.0.1:18765/", payload.wsUrl)
        assertEquals("/", payload.wsPath)
        assertEquals("0.4.1", payload.contract.version)
        assertTrue(payload.contract.capabilities.contains("webui.core.v1"))
        assertEquals("deepseek-v4.1-flash:cloud", payload.modelName)
        assertTrue(payload.expiresInSeconds > 0, "the token expires, so a reconnect needs a new bootstrap")
    }

    @Test
    fun missingWsPathIsProtocolError() {
        val result = BootstrapParser.parse("""{"token":"t","terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}}}""")

        val failed = assertIs<BootstrapResult.Failed>(result)
        assertIs<ProtocolError.MissingField>(failed.error)
        assertEquals("ws_url", (failed.error as ProtocolError.MissingField).field, "without a socket URL there is no client")
    }

    @Test
    fun limitsAreOptionalAndDefaulted() {
        val payload = assertIs<BootstrapResult.Parsed>(
            BootstrapParser.parse(
                """{"token":"t","ws_url":"ws://h/","ws_path":"/",
                    "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}}}""",
            ),
        ).payload

        // Defaults come from the gateway's own attachment policy (ingress_policy.py).
        assertEquals(4, payload.limits.attachments.maxCount)
        assertEquals(6L * 1024 * 1024, payload.limits.attachments.maxFileBytes)
        assertEquals(24L * 1024 * 1024, payload.limits.attachments.maxTotalBytes)
        assertEquals(64L * 1024, payload.limits.maxTextBytes)
        assertEquals(64L * 1024, payload.limits.envelopeReserveBytes)
        assertNull(payload.apiToken, "api_token is optional; /api/sessions is the only caller that needs it")
    }

    @Test
    fun hostActionFlagsAreNotProtocolCapabilities() {
        val payload = recorded()

        assertTrue(payload.hostActions.canRestartEngine.not(), "local gateway cannot restart itself")
        assertTrue(
            payload.hostActions.canExportDiagnostics.not(),
            "D15.1: runtime_capabilities are host action flags, so they never gate a protocol feature",
        )
        assertTrue(
            payload.contract.capabilities.none { it == "can_restart_engine" },
            "the two capabilities kinds must not be mixed",
        )
    }
}
