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
 * Onboarding's three fields, one button and its states (the Android twin of iOS's
 * `ConnectViewModel`). Every branch worth testing is here rather than in the composable: what
 * counts as a usable address, when the key is required, what a failure says, and the one rule the
 * unverified-certificate switch exists for, which is that trusting a certificate is the user's
 * decision and never the app's.
 *
 * The state is Compose state, so a Compose test and a JVM unit test read the same fields.
 */
class ConnectViewModel(
    private val gateway: GatewayConnecting,
    private val credentials: CredentialsStoring,
    /**
     * A launch that already knows its gateway (DEBUG live instrument) hands it here instead of
     * typing it: the secret never reaches a log. The values win over the store, because the
     * instrument names the gateway it is about, and what it connects with is then stored like
     * any other connect, so the next launch needs nothing.
     */
    private val prefillAddress: String? = null,
    private val prefillKey: String? = null,
) {

    var address by mutableStateOf(prefillAddress ?: credentials.address ?: "")
    // A stored key is why the app can reopen a chat without asking for the secret again.
    var key by mutableStateOf(prefillKey ?: credentials.storedKey ?: "")

    /** What this launch read, so a second look can tell an untouched screen from a typed one. */
    private val launchAddress: String = address
    private val launchKey: String = key

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

    /**
     * Both halves and nothing of the user's to do: this launch can open a gateway by itself. An
     * address without a key (or the reverse) is half a setup, which the connect screen finishes.
     */
    val hasStoredGateway: Boolean
        get() = address.trim().isNotEmpty() && key.isNotEmpty()

    val canConnect: Boolean get() = !isConnecting && hasStoredGateway

    /**
     * The store, read again: an encrypted read that ran before the phone was unlocked comes back
     * empty, and the return to the foreground is the second chance. True when the device holds a
     * gateway now, which the fields are then prefilled with. Nothing is adopted over a screen the
     * owner has started typing on: what someone is halfway through entering is theirs.
     */
    fun resolveStoredGateway(): Boolean {
        val storedAddress = (prefillAddress ?: credentials.address ?: "").trim()
        val storedKey = prefillKey ?: credentials.storedKey ?: ""
        if (storedAddress.isEmpty() || storedKey.isEmpty()) return false
        if (address != launchAddress || key != launchKey) return false
        address = storedAddress
        key = storedKey
        return true
    }

    /** Whether "try again" is an honest offer: the core decides per taxonomy entry, not the screen. */
    val offersRetry: Boolean get() = failure?.retryable == true

    /** The one command. Local refusals come first and cost no round trip. */
    suspend fun connect(): Unit {
        attempt(closeOnFailure = true)
    }

    /**
     * The launch's own attempt on the gateway the device already holds: the same bookkeeping the
     * button does, and one difference: it keeps the stack when the handshake fails. A typed
     * attempt leaves a form to fix; a launch leaves the shell's banner, and that banner's "Try
     * again" is a reconnect on the stack this keeps open. True when the gateway greeted it.
     */
    suspend fun reopenStoredGateway(): Boolean = attempt(closeOnFailure = false)

    private suspend fun attempt(closeOnFailure: Boolean): Boolean {
        if (isConnecting) return false
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
            return false
        }

        phase = ConnectPhase.Connecting
        return when (val outcome = gateway.connect(trimmed, key, allowUnverifiedCertificate)) {
            is GatewayOutcome.Connected -> {
                // The key is only kept once it was accepted; a refused one is not persisted.
                credentials.remember(trimmed, key, allowUnverifiedCertificate)
                phase = ConnectPhase.Connected(outcome.modelName)
                messageRes = null
                true
            }

            is GatewayOutcome.Failed -> {
                failure = outcome.failure
                trustSwitchIsTheRemedy =
                    outcome.failure == ConnectionFailure.TlsRejected && !allowUnverifiedCertificate
                messageRes = ConnectionCopy.text(outcome.failure, outcome.detail)
                phase = ConnectPhase.Failed
                if (closeOnFailure) gateway.disconnect()
                false
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

    /**
     * Settings → Gateway, once the store is empty: the screen must not keep the address it just
     * forgot, and a `Connected` phase must not walk the launch back into the shell it left, because
     * the connect screen reports its first composition as a success otherwise.
     */
    fun forget() {
        address = ""
        key = ""
        allowUnverifiedCertificate = false
        messageRes = null
        failure = null
        trustSwitchIsTheRemedy = false
        phase = ConnectPhase.Idle
    }
}

/** Where the gateway's address and key live: encrypted preferences in the app, a map in a test. */
interface CredentialsStoring : de.arjmandi.nanobot.companion.settings.SettingsCredentials {
    override val address: String?
    val storedKey: String?
    val allowUnverifiedCertificate: Boolean

    fun remember(address: String, key: String, allowUnverifiedCertificate: Boolean)
    fun rememberTrustDecision(allowed: Boolean)
    override fun forget()
}
