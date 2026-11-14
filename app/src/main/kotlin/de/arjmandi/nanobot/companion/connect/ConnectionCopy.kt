package de.arjmandi.nanobot.companion.connect

import androidx.annotation.StringRes
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.facade.ConnectionFailure

/**
 * The eight sentences a failed connect can show (plan.md C7.4, D29 §9) and the three local
 * refusals that never leave the phone. The core owns the copy *key*; this owns the sentence, and
 * the resource name is `connection_<key>` so a test can pair them one for one instead of trusting
 * the call site (the same rule iOS's `ConnectionCopy` follows).
 */
object ConnectionCopy {

    @StringRes
    fun text(failure: ConnectionFailure, detail: String?): Int = when (failure) {
        ConnectionFailure.AuthFailed -> R.string.connection_authFailed
        ConnectionFailure.TlsRejected -> R.string.connection_tlsRejected
        ConnectionFailure.Unreachable -> R.string.connection_unreachable
        ConnectionFailure.Timeout -> R.string.connection_timeout
        ConnectionFailure.ProtocolTooNew -> R.string.connection_protocolTooNew
        ConnectionFailure.MissingCapability -> R.string.connection_missingCapability
        ConnectionFailure.MissingField -> R.string.connection_missingField
        // 5xx may be a bad minute; a 4xx is the same answer every time (D29 §9).
        ConnectionFailure.ServerError ->
            if ((detail?.toIntOrNull() ?: 0) >= 500) R.string.connection_serverErrorRetryable else R.string.connection_serverError
    }

    /** A failure the phone catches itself, before any request is made. */
    sealed interface LocalRefusal {
        val text: Int

        data object AddressMissing : LocalRefusal {
            override val text: Int = R.string.refusal_addressMissing
        }

        data object AddressNeedsScheme : LocalRefusal {
            override val text: Int = R.string.refusal_addressNeedsScheme
        }

        data object KeyMissing : LocalRefusal {
            override val text: Int = R.string.refusal_keyMissing
        }
    }
}
