package de.arjmandi.nanobot.companion.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A gateway advertises named capabilities next to a protocol range (D15.1). A feature
 * the client can live without disappears; a protocol the client cannot speak is an
 * error, because guessing at a newer contract is how a chat screen starts lying.
 */
class CapabilitiesTest {

    private fun contract(vararg capabilities: String, min: Int = 1, max: Int = 1) =
        WebuiContract(version = "0.4.1", minProtocol = min, maxProtocol = max, capabilities = capabilities.toList())

    @Test
    fun requiresCoreCapability() {
        assertNull(contract("webui.core.v1").problems(Feature.Core), "the core capability is present")

        val missing = contract("webui.subagents.v1").problems(Feature.Core)
        assertIs<ProtocolError.MissingCapability>(missing)
        assertEquals("core", missing.feature)
    }

    @Test
    fun missingSubagentCapabilityHidesSubagents() {
        val withoutSubagents = contract("webui.core.v1")

        val error = withoutSubagents.problems(Feature.Subagents)

        assertIs<ProtocolError.MissingCapability>(error)
        assertEquals("webui.subagents.v1", error.capability, "the copy names what the host is missing")
        assertTrue(
            withoutSubagents.supports(Feature.Core) && !withoutSubagents.supports(Feature.Subagents),
            "one missing capability takes out one feature, not the client",
        )
    }

    @Test
    fun protocol2IsProtocolTooNew() {
        val future = contract("webui.core.v1", min = 2, max = 2)

        val error = future.problems(Feature.Core)

        assertIs<ProtocolError.ProtocolTooNew>(error)
        assertEquals(2, error.hostMinProtocol)
        assertEquals(1, error.clientProtocol, "the client speaks webui protocol 1")
    }
}
