package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.transport.CoreError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The eight failures the UI has to explain (plan.md §6, C7.4). The matrix is the whole point:
 * every transport failure reaches exactly one state, every state has one copy key, and the
 * retryability of each is a decision written down rather than a guess made in a view.
 */
class ConnectionStateTest {

    /** CoreError → the one state a screen shows. The compiler keeps this exhaustive. */
    private fun stateOf(error: CoreError): ConnectionState.Failed = error.connectionFailure()

    @Test
    fun mapsEveryTransportFailureToUiState() {
        val cases: List<Pair<CoreError, ConnectionState.Failed>> = listOf(
            CoreError.AuthFailed to
                ConnectionState.Failed(ConnectionFailure.AuthFailed, retryable = false),
            CoreError.TlsRejected to
                ConnectionState.Failed(ConnectionFailure.TlsRejected, retryable = false),
            CoreError.Unreachable to
                ConnectionState.Failed(ConnectionFailure.Unreachable, retryable = true),
            CoreError.Timeout to
                ConnectionState.Failed(ConnectionFailure.Timeout, retryable = true),
            CoreError.ProtocolTooNew(hostMinProtocol = 2, clientProtocol = 1) to
                ConnectionState.Failed(ConnectionFailure.ProtocolTooNew, retryable = false),
            CoreError.MissingCapability(feature = "subagents", capability = "webui.subagents.v1") to
                ConnectionState.Failed(ConnectionFailure.MissingCapability, retryable = false),
            CoreError.MissingField(field = "token") to
                ConnectionState.Failed(ConnectionFailure.MissingField, retryable = false),
            CoreError.ServerError(status = 500) to
                ConnectionState.Failed(ConnectionFailure.ServerError, retryable = true),
        )

        assertEquals(8, cases.size, "the taxonomy is eight entries; a ninth needs a decision and a copy line")
        cases.forEach { (error, expected) ->
            assertEquals(expected.failure, stateOf(error).failure, "$error maps to one state")
            assertEquals(expected.retryable, stateOf(error).retryable, "$error has one retryability")
        }
    }

    @Test
    fun aServerErrorIsOnlyRetryableWhenItIsTheServer() {
        assertEquals(true, stateOf(CoreError.ServerError(status = 503)).retryable, "a 5xx is worth another attempt")
        assertEquals(false, stateOf(CoreError.ServerError(status = 404)).retryable, "a 4xx will say the same thing twice")
    }

    @Test
    fun aMissingCapabilityKeepsItsDetail() {
        val state = stateOf(CoreError.MissingCapability(feature = "subagents", capability = "webui.subagents.v1"))

        assertEquals("webui.subagents.v1", state.detail, "the feature that is hidden is named, not guessed")
    }

    @Test
    fun everyFailureHasItsOwnCopyKey() {
        val keys = ConnectionFailure.entries.map { it.copyKey }

        assertEquals(ConnectionFailure.entries.size, keys.toSet().size, "two states sharing a sentence is a bug")
        assertTrue(keys.all { it.startsWith("connection.") && it.length > "connection.".length }, "keys are namespaced copy ids: $keys")
    }

    @Test
    fun protocolTooNewNamesBothVersions() {
        val state = stateOf(CoreError.ProtocolTooNew(hostMinProtocol = 3, clientProtocol = 1))

        assertEquals("3", state.detail?.substringBefore('/'), "the screen can say which protocol the host wants")
        assertEquals("1", state.detail?.substringAfter('/'), "and which one this app speaks")
    }
    /**
     * A failed connect answers with the state a screen renders, not only with the error the
     * taxonomy was derived from: Swift cannot read the internal `connectionFailure()` mapping
     * (C8.2), and re-deriving the eight entries in Swift would be a second taxonomy to keep in
     * step. The result carries both, and they agree.
     */
    @Test
    fun aFailedConnectCarriesTheStateTheScreenRenders() {
        val error = CoreError.ServerError(status = 503)
        val failure = error.connectionFailure()
        val result = ConnectResult.Failed(error = error, failure = failure.failure, detail = failure.detail)

        assertEquals(error, result.error, "the transport's own word is kept for logs and tests")
        assertEquals(ConnectionFailure.ServerError, result.failure, "the screen switches on the taxonomy")
        assertEquals("503", result.detail, "and has the one detail its sentence needs")
        assertTrue(result.failure.retryable, "a 503 is worth trying again")
    }

}
