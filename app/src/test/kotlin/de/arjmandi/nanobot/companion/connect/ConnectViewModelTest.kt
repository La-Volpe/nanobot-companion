package de.arjmandi.nanobot.companion.connect

import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.core.GatewayConnecting
import de.arjmandi.nanobot.companion.core.GatewayOutcome
import de.arjmandi.nanobot.companion.facade.ConnectionFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Onboarding (plan.md A1). The red line the plan names is the unverified-certificate switch: it is
 * the user's decision about one gateway, it is off until they say otherwise, and turning it on
 * never retries by itself. The rest is the button's own rules and the copy each failure picks.
 */
class ConnectViewModelTest {

    /** A gateway that answers with whatever the test asked for, and records what it was told. */
    private class FakeGateway(var outcome: GatewayOutcome = GatewayOutcome.Connected("deepseek")) : GatewayConnecting {
        var lastAddress: String? = null
        var lastKey: String? = null
        var lastAllowUnverified: Boolean? = null
        var disconnects: Int = 0
        var connects: Int = 0

        override suspend fun connect(address: String, key: String, allowUnverified: Boolean): GatewayOutcome {
            lastAddress = address
            lastKey = key
            lastAllowUnverified = allowUnverified
            connects += 1
            return outcome
        }

        override fun disconnect() {
            disconnects += 1
        }
    }

    private class MemoryCredentials(
        override var address: String? = null,
        override var allowUnverifiedCertificate: Boolean = false,
    ) : CredentialsStoring {
        override var storedKey: String? = null
        var forgotten: Int = 0

        override fun remember(address: String, key: String, allowUnverifiedCertificate: Boolean) {
            this.address = address
            this.storedKey = key
            this.allowUnverifiedCertificate = allowUnverifiedCertificate
        }

        override fun rememberTrustDecision(allowed: Boolean) {
            allowUnverifiedCertificate = allowed
        }

        override fun forget() {
            address = null
            storedKey = null
            allowUnverifiedCertificate = false
            forgotten += 1
        }
    }

    private fun model(gateway: FakeGateway, credentials: MemoryCredentials = MemoryCredentials()): ConnectViewModel =
        ConnectViewModel(gateway, credentials, prefillKey = "s3cr3t").also {
            it.address = "http://127.0.0.1:18765"
        }

    @Test
    fun unverifiedCertRequiresToggle() = runTest {
        val gateway = FakeGateway(outcome = GatewayOutcome.Failed(ConnectionFailure.TlsRejected, null))
        val credentials = MemoryCredentials()
        val model = model(gateway, credentials)

        // Off until the user says otherwise: the app never trusts a certificate on its own.
        model.connect()

        assertEquals(false, gateway.lastAllowUnverified, "the first attempt is always verified")
        assertEquals(false, credentials.allowUnverifiedCertificate, "and nothing was remembered as trusted")
        assertEquals(ConnectionFailure.TlsRejected, model.failure)
        assertTrue(model.trustSwitchIsTheRemedy, "the switch is the remedy this screen offers")
        assertEquals(R.string.connection_tlsRejected, model.messageRes)
        assertEquals(1, gateway.connects, "a refusal does not retry by itself")

        // Turning it on is a decision, not a retry.
        model.rememberTrustDecision(true)
        assertTrue(credentials.allowUnverifiedCertificate, "the decision is remembered for this gateway")
        assertFalse(model.trustSwitchIsTheRemedy, "the hint goes away once the switch is on")
        assertEquals(1, gateway.connects, "turning the switch on connects nothing")

        // The next press is the user's, and it carries the decision.
        gateway.outcome = GatewayOutcome.Connected("deepseek-v4.1-flash:cloud")
        model.connect()
        assertEquals(true, gateway.lastAllowUnverified)
        assertEquals(ConnectPhase.Connected("deepseek-v4.1-flash:cloud"), model.phase)
        assertNull(model.messageRes, "a connected screen has no error under the fields")
        assertEquals("s3cr3t", credentials.storedKey, "the key is kept once the gateway accepted it")
    }

    @Test
    fun aRefusedKeyIsNotRemembered() = runTest {
        val gateway = FakeGateway(outcome = GatewayOutcome.Failed(ConnectionFailure.AuthFailed, null))
        val credentials = MemoryCredentials()
        val model = model(gateway, credentials)

        model.connect()

        assertNull(credentials.storedKey, "a key the gateway refused is never persisted")
        assertEquals(R.string.connection_authFailed, model.messageRes)
        assertEquals(1, gateway.disconnects, "a refused connect closes what it opened")
        assertFalse(model.offersRetry, "an auth failure is not worth a retry: the key is wrong")
    }

    @Test
    fun anUnreachableGatewayOffersRetry() = runTest {
        val gateway = FakeGateway(outcome = GatewayOutcome.Failed(ConnectionFailure.Unreachable, null))
        val model = model(gateway)

        model.connect()

        assertTrue(model.offersRetry, "a gateway that is not up yet is worth trying again")
        assertEquals(R.string.connection_unreachable, model.messageRes)
    }

    @Test
    fun a5xxSaysTryAgainInAMoment() = runTest {
        val gateway = FakeGateway(outcome = GatewayOutcome.Failed(ConnectionFailure.ServerError, "503"))
        val model = model(gateway)

        model.connect()

        assertEquals(R.string.connection_serverErrorRetryable, model.messageRes)
    }

    @Test
    fun localRefusalsCostNoRoundTrip() = runTest {
        val gateway = FakeGateway()
        val model = model(gateway)

        model.address = "  "
        model.connect()
        assertEquals(R.string.refusal_addressMissing, model.messageRes)

        model.address = "127.0.0.1:18765"
        model.connect()
        assertEquals(R.string.refusal_addressNeedsScheme, model.messageRes)

        model.address = "http://127.0.0.1:18765"
        model.key = ""
        model.connect()
        assertEquals(R.string.refusal_keyMissing, model.messageRes)

        assertEquals(0, gateway.connects, "a refusal the phone can make itself never reaches the socket")
    }

    @Test
    fun theButtonIsDisabledUntilThereIsAnAddressAndAKey() = runTest {
        val model = model(FakeGateway())

        assertTrue(model.canConnect)
        model.key = ""
        assertFalse(model.canConnect)
        model.key = "s3cr3t"
        model.address = ""
        assertFalse(model.canConnect)
    }

    @Test
    fun typingClearsTheRefusalUnderTheFields() = runTest {
        val gateway = FakeGateway(outcome = GatewayOutcome.Failed(ConnectionFailure.AuthFailed, null))
        val model = model(gateway)
        model.connect()
        assertEquals(ConnectPhase.Failed, model.phase)

        model.clearMessage()

        assertNull(model.messageRes)
        assertEquals(ConnectPhase.Idle, model.phase, "the screen is editable again, not stuck on the failure")
    }
}
