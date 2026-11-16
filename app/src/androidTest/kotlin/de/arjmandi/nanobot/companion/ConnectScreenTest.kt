package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.connect.ConnectScreen
import de.arjmandi.nanobot.companion.connect.ConnectViewModel
import de.arjmandi.nanobot.companion.connect.CredentialsStoring
import de.arjmandi.nanobot.companion.core.GatewayConnecting
import de.arjmandi.nanobot.companion.core.GatewayOutcome
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Onboarding's taps (plan.md A1's Compose half). The view model owns the rules —
 * `ConnectViewModelTest` is where those are asserted — and this is the part only a real
 * composition can prove: the button is disabled until there is an address and a key, an address
 * without a scheme raises its sentence without a socket, and the trust switch is on the screen,
 * off until the user turns it on. The content is the screen, not the Activity: a Compose test that
 * launched the app would read whatever is in this device's preferences.
 */
@RunWith(AndroidJUnit4::class)
class ConnectScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class NeverConnects : GatewayConnecting {
        var attempts: Int = 0

        override suspend fun connect(address: String, key: String, allowUnverified: Boolean): GatewayOutcome {
            attempts += 1
            return GatewayOutcome.Connected("deepseek")
        }

        override fun disconnect() = Unit
    }

    private class MemoryCredentials : CredentialsStoring {
        override var address: String? = null
        override var storedKey: String? = null
        override var allowUnverifiedCertificate: Boolean = false

        override fun remember(address: String, key: String, allowUnverifiedCertificate: Boolean) {
            this.address = address
            this.storedKey = key
            this.allowUnverifiedCertificate = allowUnverifiedCertificate
        }

        override fun rememberTrustDecision(allowed: Boolean) {
            allowUnverifiedCertificate = allowed
        }

        override fun forget() = Unit
    }

    private fun screen(): ConnectViewModel {
        val model = ConnectViewModel(NeverConnects(), MemoryCredentials())
        compose.setContent {
            NanobotTheme { ConnectScreen(model = model, onConnected = {}) }
        }
        return model
    }

    @Test
    fun theButtonIsDisabledUntilThereIsAnAddressAndAKey() {
        screen()

        compose.onNodeWithTag("connect.submit").assertIsNotEnabled()

        compose.onNodeWithTag("connect.key").performTextInput("s3cr3t")
        compose.onNodeWithTag("connect.submit").assertIsNotEnabled()

        compose.onNodeWithTag("connect.address").performTextInput("http://127.0.0.1:18765")
        compose.onNodeWithTag("connect.submit").assertIsEnabled()
    }

    @Test
    fun anAddressWithoutASchemeSaysSoBeforeAnyRoundTrip() {
        val model = screen()

        compose.onNodeWithTag("connect.address").performTextInput("127.0.0.1:18765")
        compose.onNodeWithTag("connect.key").performTextInput("s3cr3t")
        compose.onNodeWithTag("connect.submit").performClick()

        compose.onNodeWithTag("connect.error").assertIsDisplayed()
        assert(model.messageRes == R.string.refusal_addressNeedsScheme) {
            "the screen shows the scheme refusal, not a socket failure"
        }
    }

    /** T52: the name and the day/night mark stay, so the screen says plainly what the app is not. */
    @Test
    fun theScreenSaysPlainlyWhoThisAppIsNot() {
        screen()

        compose.onNodeWithTag("connect.disclaimer")
            .assertIsDisplayed()
            .assertTextEquals("Not affiliated with HKUDS/nanobot.")
    }

    @Test
    fun theUnverifiedCertificateSwitchIsOffAndTheUsersToTurnOn() {
        screen()

        compose.onNodeWithTag("connect.allowUnverified").assertIsDisplayed()
        compose.onNodeWithTag("connect.allowUnverified").assertIsOff()

        compose.onNodeWithTag("connect.allowUnverified").performClick()
        compose.onNodeWithTag("connect.allowUnverified").assertIsOn()
    }
}
