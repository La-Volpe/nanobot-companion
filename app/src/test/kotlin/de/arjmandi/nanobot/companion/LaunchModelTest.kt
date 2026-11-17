package de.arjmandi.nanobot.companion

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which screen a launch opens on, and why (the Android twin of iOS's `LaunchModelTests`). The store
 * is the reason a second launch is not a second setup; a gateway that stopped answering is the
 * reason a failed one is not onboarding either.
 *
 * The gateway is a lambda here on purpose: what this model decides is the screen, and the attempt's
 * own bookkeeping is `ConnectViewModelTest`'s. The pair, a real failed reopen landing on the shell
 * with its banner, is `LaunchScreenTest`'s, on a device.
 */
class LaunchModelTest {

    private class Attempts {
        var connects = 0
    }

    @Test
    fun aStoredGatewayOpensTheShellWithoutAsking() = runTest {
        val attempts = Attempts()
        val model = LaunchModel(scope = this, hasStoredGateway = { true }, connect = { attempts.connects += 1 })

        // The first paint is neither the question nor a guess: onboarding shown for a moment is
        // onboarding shown.
        assertEquals(LaunchModel.Screen.Deciding, model.screen, "a launch begins by deciding")

        model.begin()
        runCurrent()

        assertEquals(LaunchModel.Screen.Shell, model.screen, "a gateway the device already holds is not a first launch")
        assertEquals(1, attempts.connects, "the stored gateway is reopened, not assumed")
    }

    /**
     * The address and the key are right, the gateway is not there. The banner and its retry belong
     * to the shell, so that is where this lands.
     */
    @Test
    fun aGatewayThatStoppedAnsweringIsStillTheShell() = runTest {
        val attempts = Attempts()
        // The attempt answers nothing either way: how it went is the connect model's bookkeeping
        // and the shell's banner. This model decides only which screen draws first.
        val model = LaunchModel(scope = this, hasStoredGateway = { true }, connect = { attempts.connects += 1 })

        model.begin()
        runCurrent()

        assertEquals(LaunchModel.Screen.Shell, model.screen, "onboarding is not where a gateway that stopped answering goes")
        assertEquals(1, attempts.connects, "it tried instead of assuming")
    }

    @Test
    fun nothingStoredAsksForTheGateway() = runTest {
        val attempts = Attempts()
        val model = LaunchModel(scope = this, hasStoredGateway = { false }, connect = { attempts.connects += 1 })

        model.begin()
        runCurrent()

        assertEquals(LaunchModel.Screen.Onboarding, model.screen)
        assertEquals(0, attempts.connects, "a first launch has nothing to attempt")
    }

    @Test
    fun oneLaunchDecidesOnce() = runTest {
        val attempts = Attempts()
        val model = LaunchModel(scope = this, hasStoredGateway = { true }, connect = { attempts.connects += 1 })

        model.begin()
        runCurrent()
        model.begin()
        runCurrent()

        assertEquals(1, attempts.connects, "a second decision would be a second handshake and a second token spent")
    }

    /**
     * An encrypted read that ran before the phone was unlocked comes back empty. Coming to the
     * foreground is the second look.
     */
    @Test
    fun aSecondLookAtTheStoreCanStillFindTheGateway() = runTest {
        val attempts = Attempts()
        var stored = false
        val model = LaunchModel(scope = this, hasStoredGateway = { stored }, connect = { attempts.connects += 1 })

        model.begin()
        runCurrent()
        assertEquals(LaunchModel.Screen.Onboarding, model.screen)

        stored = true
        model.resumed()
        runCurrent()

        assertEquals(LaunchModel.Screen.Shell, model.screen, "a launch that began without a readable store can still reopen")
        assertEquals(1, attempts.connects)
    }

    /** The other half of the same rule: a shell that is up is not re-decided by a foreground. */
    @Test
    fun aForegroundLeavesARunningShellAlone() = runTest {
        val attempts = Attempts()
        val model = LaunchModel(scope = this, hasStoredGateway = { true }, connect = { attempts.connects += 1 })
        model.begin()
        runCurrent()

        model.resumed()
        runCurrent()

        assertEquals(LaunchModel.Screen.Shell, model.screen)
        assertEquals(1, attempts.connects, "nothing to decide on a screen that is already in the shell")
    }

    /** The connect screen's success is the other way in, and disconnecting is the one way back. */
    @Test
    fun onboardingAndDisconnectMoveTheScreen() = runTest {
        val model = LaunchModel(scope = this, hasStoredGateway = { false }, connect = {})

        model.begin()
        runCurrent()
        assertEquals(LaunchModel.Screen.Onboarding, model.screen)

        model.finishedOnboarding()
        assertEquals(LaunchModel.Screen.Shell, model.screen)

        model.signedOut()
        assertEquals(LaunchModel.Screen.Onboarding, model.screen)
    }

    /**
     * Disconnecting is the owner's decision. The foreground's second look is for a launch that
     * found nothing, not a way back into a gateway they just left.
     */
    @Test
    fun aDisconnectIsNotUndoneByComingBackToTheForeground() = runTest {
        val attempts = Attempts()
        var stored = true
        val model = LaunchModel(scope = this, hasStoredGateway = { stored }, connect = { attempts.connects += 1 })
        model.begin()
        runCurrent()
        model.signedOut()
        stored = true

        model.resumed()
        runCurrent()

        assertEquals(LaunchModel.Screen.Onboarding, model.screen)
        assertEquals(1, attempts.connects, "the store is empty after a disconnect")
    }
}
