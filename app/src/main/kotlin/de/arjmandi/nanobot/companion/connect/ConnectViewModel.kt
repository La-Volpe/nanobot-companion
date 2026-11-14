package de.arjmandi.nanobot.companion.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.arjmandi.nanobot.companion.core.GatewayConnecting
import de.arjmandi.nanobot.companion.core.GatewayOutcome
import de.arjmandi.nanobot.companion.facade.ConnectionFailure

/** Where a connect attempt is, as the button draws it. */
sealed interface ConnectPhase {
    data object Idle : ConnectPhase
    data object Connecting : ConnectPhase
    data class Connected(val modelName: String?) : ConnectPhase
    data object Failed : ConnectPhase
}

/**
 * Onboarding's three fields, one button and its states (plan.md A1, the Android twin of iOS's
 * `ConnectViewModel`). Every branch worth testing is here rather than in the composable: what
 * counts as a usable address, when the key is required, what a failure says, and the one rule the
 * unverified-certificate switch exists for — it is the user's decision, never the app's.
 *
 * The state is Compose state, so a Compose test and a JVM unit test read the same fields.
 */
class ConnectViewModel(
    private val gateway: GatewayConnecting,
    private val credentials: CredentialsStoring,
    prefillKey: String? = null,
) {

    var address by mutableStateOf(credentials.address ?: "")
    var key by mutableStateOf(prefillKey ?: "")

    /** The switch. Set through [rememberTrustDecision] so the decision is also stored. */
    var allowUnverifiedCertificate by mutableStateOf(credentials.allowUnverifiedCertificate)
        private set

    var phase by mutableStateOf<ConnectPhase>(ConnectPhase.Idle)
        private set

    /** The sentence under the fields, when there is one. A resource id: the screen resolves it. */
    var messageRes by mutableStateOf<Int?>(null)
        private set

    /** The failure the message belongs to, for the retry rule and for the tests. */
    var failure by mutableStateOf<ConnectionFailure?>(null)
        private set

    /** A TLS refusal is the one failure whose remedy is a switch on this screen. */
    var trustSwitchIsTheRemedy by mutableStateOf(false)
        private set

    val isConnecting: Boolean get() = phase is ConnectPhase.Connecting

    val canConnect: Boolean
        get() = !isConnecting && address.trim().isNotEmpty() && key.isNotEmpty()

    /** Whether "try again" is an honest offer: the core decides per taxonomy entry, not the screen. */
    val offersRetry: Boolean get() = failure?.retryable == true

    /** The one command. Local refusals come first and cost no round trip. */
    suspend fun connect(): Unit {
        if (isConnecting) return
        messageRes = null
        trustSwitchIsTheRemedy = false
        failure = null

        val trimmed = address.trim()
        val refusal = when {
            trimmed.isEmpty() -> ConnectionCopy.LocalRefusal.AddressMissing
            !trimmed.contains("://") -> ConnectionCopy.LocalRefusal.AddressNeedsScheme
            key.isEmpty() -> ConnectionCopy.LocalRefusal.KeyMissing
            else -> null
        }
        if (refusal != null) {
            messageRes = refusal.text
            phase = ConnectPhase.Failed
            return
        }

        phase = ConnectPhase.Connecting
        when (val outcome = gateway.connect(trimmed, key, allowUnverifiedCertificate)) {
            is GatewayOutcome.Connected -> {
                // The key is only kept once it was accepted; a refused one is not persisted.
                credentials.remember(trimmed, key, allowUnverifiedCertificate)
                phase = ConnectPhase.Connected(outcome.modelName)
                messageRes = null
            }

            is GatewayOutcome.Failed -> {
                failure = outcome.failure
                trustSwitchIsTheRemedy =
                    outcome.failure == ConnectionFailure.TlsRejected && !allowUnverifiedCertificate
                messageRes = ConnectionCopy.text(outcome.failure, outcome.detail)
                phase = ConnectPhase.Failed
                gateway.disconnect()
            }
        }
    }

    /**
     * The switch is the user's decision about one gateway; turning it on never retries by itself,
     * because a silent retry after trusting a certificate is the wrong way round.
     */
    fun rememberTrustDecision(allowed: Boolean) {
        allowUnverifiedCertificate = allowed
        credentials.rememberTrustDecision(allowed)
        if (allowed) trustSwitchIsTheRemedy = false
    }

    /** Typing clears the sentence that belongs to what was typed before. */
    fun clearMessage() {
        messageRes = null
        trustSwitchIsTheRemedy = false
        failure = null
        if (phase is ConnectPhase.Failed) phase = ConnectPhase.Idle
    }
}

/** Where the gateway's address and key live: encrypted preferences in the app, a map in a test. */
interface CredentialsStoring {
    val address: String?
    val storedKey: String?
    val allowUnverifiedCertificate: Boolean

    fun remember(address: String, key: String, allowUnverifiedCertificate: Boolean)
    fun rememberTrustDecision(allowed: Boolean)
    fun forget()
}
