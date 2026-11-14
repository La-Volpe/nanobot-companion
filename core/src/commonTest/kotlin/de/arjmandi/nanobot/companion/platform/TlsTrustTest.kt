package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trust decision a self-signed home gateway needs (plan.md C3.2, D21, P2). The policy
 * object existed and was tested; this is the rule that turns it into a decision a platform's
 * TLS stack can act on, kept pure so both platforms can share it and a test can walk every
 * cell of the matrix.
 *
 * What it must never do: accept an unknown certificate for a host the user did not configure,
 * or accept one for something that is not the server's certificate at all.
 */
class TlsTrustTest {

    private val trusting = TlsPolicy(trustSelfSignedCertificates = true, host = "home.lan")

    @Test
    fun anUntrustedCertificateIsAcceptedOnlyForTheConfiguredHost() {
        assertEquals(
            TlsTrust.AcceptServerTrust,
            TlsTrustRule.decide(trusting, isServerTrust = true, challengeHost = "home.lan"),
        )
    }

    @Test
    fun theSwitchOffHandsTheDecisionToTheSystem() {
        assertEquals(
            TlsTrust.Default,
            TlsTrustRule.decide(TlsPolicy.DEFAULT, isServerTrust = true, challengeHost = "home.lan"),
        )
        assertEquals(
            TlsTrust.Default,
            TlsTrustRule.decide(TlsPolicy(host = "home.lan"), isServerTrust = true, challengeHost = "home.lan"),
            "the address alone trusts nothing; the switch is the decision",
        )
    }

    @Test
    fun trustingOneGatewayDoesNotTrustAnother() {
        assertEquals(
            TlsTrust.Default,
            TlsTrustRule.decide(trusting, isServerTrust = true, challengeHost = "evil.example"),
            "a redirect or a second connection must not inherit the switch",
        )
        assertEquals(
            TlsTrust.Default,
            TlsTrustRule.decide(trusting, isServerTrust = true, challengeHost = null),
            "no host to match is not a host that matches",
        )
        assertEquals(
            TlsTrust.Default,
            TlsTrustRule.decide(trusting.copy(host = null), isServerTrust = true, challengeHost = "home.lan"),
            "a policy with no host cannot say which certificate it trusts",
        )
    }

    @Test
    fun onlyServerTrustChallengesAreTouched() {
        // Basic or client-certificate challenges are the system's business even with the
        // switch on: the switch is about one certificate, not about every authentication.
        assertEquals(
            TlsTrust.Default,
            TlsTrustRule.decide(trusting, isServerTrust = false, challengeHost = "home.lan"),
        )
    }

    @Test
    fun theHostnameCheckIsNeverTurnedOffByTheSwitch() {
        // `verifiesHostname` is a fact about the policy, not a knob: a trusting policy still
        // reports true, and the rule keeps matching hosts rather than skipping the comparison.
        assertTrue(trusting.verifiesHostname)
        assertEquals(
            TlsTrust.Default,
            TlsTrustRule.decide(trusting.copy(verifiesHostname = false), isServerTrust = true, challengeHost = "evil.example"),
            "disabling the check would not make a wrong host acceptable",
        )
    }

    @Test
    fun aGatewayAddressYieldsTheHostTheCertificateMustMatch() {
        val policy = TlsPolicy.forGateway("https://home.lan:8443/", trustSelfSignedCertificates = true)

        assertEquals("home.lan", policy.host)
        assertTrue(policy.trustSelfSignedCertificates)
        assertTrue(policy.verifiesHostname, "the port and path are dropped, the hostname is kept")
    }

    @Test
    fun addressesTheUserActuallyTypesAllYieldAHost() {
        assertEquals("192.168.1.10", TlsPolicy.forGateway("http://192.168.1.10:18765", true).host)
        assertEquals("your-gateway.example", TlsPolicy.forGateway("https://your-gateway.example", true).host)
        assertEquals("home.lan", TlsPolicy.forGateway("home.lan:18765", true).host, "no scheme is the common paste")
        assertEquals("home.lan", TlsPolicy.forGateway("  https://home.lan/  ", true).host, "whitespace and a trailing slash are noise")
        assertEquals("home.lan", TlsPolicy.forGateway("http://user:pass@home.lan:18765", true).host, "credentials never become the host")
        assertNull(TlsPolicy.forGateway("", true).host, "an empty address has no gateway to trust")
        assertNull(TlsPolicy.forGateway("   ", true).host)
    }

    @Test
    fun theDefaultPolicyIsBuiltFromTheAddressWithTheSwitchOff() {
        val policy = TlsPolicy.forGateway("https://home.lan", trustSelfSignedCertificates = false)

        assertEquals("home.lan", policy.host)
        assertFalse(policy.trustSelfSignedCertificates)
        assertFalse(policy.requiresConfirmation, "there is no warning to show while the switch is off")
    }
}
