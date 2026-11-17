package de.arjmandi.nanobot.companion.voice

import android.content.SharedPreferences

/**
 * The one hint that must survive a launch: "Hold to talk" is worth saying once to someone
 * who taps the microphone, and never again. Kept beside the settings store, because it is the same
 * kind of thing: a decision the reader made by doing something.
 */
class PreferencesVoiceHints(private val preferences: SharedPreferences) : VoiceHints {

    override fun hasSeenHoldHint(): Boolean = preferences.getBoolean(HOLD_HINT_SEEN, false)

    override fun markHoldHintSeen() {
        preferences.edit().putBoolean(HOLD_HINT_SEEN, true).apply()
    }

    private companion object {
        const val HOLD_HINT_SEEN = "voice.hold_hint_seen"
    }
}
