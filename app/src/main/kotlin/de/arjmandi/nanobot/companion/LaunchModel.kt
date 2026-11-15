package de.arjmandi.nanobot.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Which screen a launch opens on, and why (the Android twin of iOS's `LaunchModel`). The store is
 * the reason a second launch is not a second setup: a gateway the device already holds puts the app
 * in the shell, and a gateway that no longer answers is that shell's banner — with its own "Try
 * again" — rather than the address and key questions again.
 *
 * A model rather than a `LaunchedEffect` on the screen, for the usual reason: the rule is worth a
 * test, and a rule inside a composable cannot be asked which screen it chose.
 */
class LaunchModel(
    /** The screen's own scope: the attempt runs behind the deciding screen, not on the caller. */
    private val scope: CoroutineScope,
    /**
     * Whether the device holds both halves of a gateway, resolved by the connect model, because it
     * is the one place that knows a launch-carried address wins over a stored one. Asked again on
     * a second look: an encrypted read that ran before the phone was unlocked comes back empty.
     */
    private val hasStoredGateway: () -> Boolean,
    /**
     * One attempt on the stored gateway. What it returned is not needed here: a failure is the
     * shell's banner, and the connect model is what put it there.
     */
    private val connect: suspend () -> Unit,
) {

    enum class Screen {
        /**
         * The store has not been read and the gateway has not answered yet. The first paint is
         * neither the question nor a guess, which is what makes "it reopened" true: onboarding
         * shown for a moment is onboarding shown.
         */
        Deciding,

        /** Nothing on this device can be reopened: the address and the key are the user's to type. */
        Onboarding,

        /** The shell, whether or not the socket is up — the banner says the rest. */
        Shell,
    }

    var screen by mutableStateOf(Screen.Deciding)
        private set

    private var began = false

    /**
     * Settings → Gateway → Disconnect is the owner's decision, not an unfinished launch: the second
     * look a foreground makes is not a way back in behind their back.
     */
    private var leftByTheOwner = false

    /** One launch decision, made before any screen can be drawn. */
    fun begin() {
        if (began) return
        began = true
        decide()
    }

    /**
     * Back to the foreground while this launch has still not found a gateway. A store read can come
     * back empty on a phone that was locked when it ran, so the store gets a second look once there
     * is someone looking at the screen. Only an unanswered first launch is re-decided: what the
     * owner typed on the connect screen is theirs, not ours.
     */
    fun resumed() {
        if (!began || leftByTheOwner || screen != Screen.Onboarding) return
        decide()
    }

    /** The connect screen's own success: the user typed a gateway this time. */
    fun finishedOnboarding() {
        began = true
        leftByTheOwner = false
        screen = Screen.Shell
    }

    /** Settings → Gateway forgot the store, so the questions come back. */
    fun signedOut() {
        leftByTheOwner = true
        screen = Screen.Onboarding
    }

    private fun decide() {
        if (!hasStoredGateway()) {
            screen = Screen.Onboarding
            return
        }
        scope.launch {
            // The attempt happens behind the deciding screen, so what the owner sees is the answer.
            connect()
            // Reaching the shell is not the same claim as reaching the gateway: the shell draws the
            // connection state, so a gateway that stopped answering lands here with its sentence
            // and its retry instead of asking for an address it already has.
            screen = Screen.Shell
        }
    }
}
