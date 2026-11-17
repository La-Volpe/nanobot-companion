package de.arjmandi.nanobot.companion.connect

import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.facade.ConnectionFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The taxonomy's pairing with Android's strings. Every entry gets one
 * sentence, a 5xx gets the "try again in a moment" variant, and a 4xx does not, which is the only
 * copy decision the taxonomy leaves to the platform besides the sentence itself.
 */
class ConnectionCopyTest {

    @Test
    fun everyTaxonomyEntryHasOneSentence() {
        assertEquals(
            R.string.connection_authFailed,
            ConnectionCopy.text(ConnectionFailure.AuthFailed, null),
        )
        assertEquals(
            R.string.connection_tlsRejected,
            ConnectionCopy.text(ConnectionFailure.TlsRejected, null),
        )
        assertEquals(
            R.string.connection_unreachable,
            ConnectionCopy.text(ConnectionFailure.Unreachable, null),
        )
        assertEquals(
            R.string.connection_timeout,
            ConnectionCopy.text(ConnectionFailure.Timeout, null),
        )
        assertEquals(
            R.string.connection_protocolTooNew,
            ConnectionCopy.text(ConnectionFailure.ProtocolTooNew, null),
        )
        assertEquals(
            R.string.connection_missingCapability,
            ConnectionCopy.text(ConnectionFailure.MissingCapability, null),
        )
        assertEquals(
            R.string.connection_missingField,
            ConnectionCopy.text(ConnectionFailure.MissingField, null),
        )
    }

    @Test
    fun aServerErrorSaysTryAgainOnlyWhenItMightHelp() {
        assertTrue(ConnectionFailure.ServerError.retryable, "a 5xx is worth one more try")

        assertEquals(
            R.string.connection_serverErrorRetryable,
            ConnectionCopy.text(ConnectionFailure.ServerError, "503"),
        )
        assertEquals(
            R.string.connection_serverError,
            ConnectionCopy.text(ConnectionFailure.ServerError, "400"),
        )
        assertEquals(
            R.string.connection_serverError,
            ConnectionCopy.text(ConnectionFailure.ServerError, null),
            "a refusal with no status is not promised to pass",
        )
    }
}
