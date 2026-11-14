package de.arjmandi.nanobot.companion.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A home gateway is usually self-signed, which is why onboarding has one explicit switch
 * (plan.md C3.2, D-risk table). The switch is off until the user turns it on, and turning
 * it on never disables hostname checks: the certificate may be unknown, the host may not.
 */
class TlsPolicyTest {

    @Test
    fun defaultsToOff() {
        assertFalse(TlsPolicy.DEFAULT.trustSelfSignedCertificates, "an unknown certificate is a decision the user makes")
        assertTrue(TlsPolicy.DEFAULT.verifiesHostname, "the hostname is always checked")
    }

    @Test
    fun trustingChangesOnlyTheCertificateCheck() {
        val trusting = TlsPolicy(trustSelfSignedCertificates = true)

        assertTrue(trusting.trustSelfSignedCertificates)
        assertTrue(trusting.verifiesHostname, "trusting a certificate must not skip the hostname")
        assertTrue(trusting.requiresConfirmation, "the switch carries the warning copy in onboarding")
        assertFalse(TlsPolicy.DEFAULT.requiresConfirmation)
    }

    @Test
    fun rememberedPerGatewayNotGlobal() {
        val first = TlsPolicy(trustSelfSignedCertificates = true, host = "home.lan")
        val second = TlsPolicy(host = "chat.example.com")

        assertTrue(first.trustSelfSignedCertificates)
        assertFalse(second.trustSelfSignedCertificates, "trusting one gateway does not trust every gateway")
    }
}
