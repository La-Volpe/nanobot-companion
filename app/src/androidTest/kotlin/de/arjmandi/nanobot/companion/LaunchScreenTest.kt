package de.arjmandi.nanobot.companion

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Which screen a cold launch opens on, on the real activity and the real store (the Android half of
 * iOS's `StoredLaunchTests`). The launch decision is `LaunchModelTest`'s; this is the part only a
 * device proves: the store a previous launch wrote is what the next launch acts on, so a gateway
 * that stopped answering is the shell's banner and its "Try again" rather than the address and key
 * questions again.
 *
 * These cases run against encrypted preferences, so each one leaves the store as it found it.
 */
@RunWith(AndroidJUnit4::class)
class LaunchScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun stored() = StoredCredentials(
        context,
        context.getSharedPreferences(StoredCredentials.PREFERENCES, Context.MODE_PRIVATE),
    )

    private fun shown(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) { shown(tag) }
    }

    @Test
    fun anEmptyStoreAsksForTheGateway() {
        stored().forget()

        ActivityScenario.launch(MainActivity::class.java).use {
            await("connect.address")
            compose.onNodeWithTag("connect.address").assertIsDisplayed()
        }
    }

    /**
     * The address and the key are right, the gateway is not there. Onboarding is not where a gateway
     * that stopped answering goes: the shell draws the connection state, and its retry is the
     * reconnect the banner offers.
     */
    @Test
    fun aStoredGatewayThatNoLongerAnswersIsTheBannerNotOnboarding() {
        // Nothing listens on port 1, so the attempt fails by connection refused rather than a timeout.
        stored().remember("http://10.0.2.2:1", "the-secret", allowUnverifiedCertificate = false)

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                await("chat.openDrawer")
                assertTrue(
                    compose.onAllNodesWithTag("connect.address").fetchSemanticsNodes().isEmpty(),
                    "onboarding came back for a gateway this device already holds",
                )
                await("states.banner")
                compose.onNodeWithTag("states.retry").assertIsDisplayed()
            }
        } finally {
            stored().forget()
        }
    }
}
