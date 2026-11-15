package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.wire.SettingsState
import kotlin.math.roundToInt

/**
 * The context ring's two numbers (C6.7). The denominator is read in the WebUI's order
 * (`thread/ContextInspector.tsx:52`): what a turn carried with its usage, then the chat's own
 * context, then the preset's configured window. A compacted page can carry the usage and not
 * the window, and a ring with no denominator is a blank where a number belongs (T37).
 */
public object ContextUsage {

    /** The WebUI's arithmetic (`thread/ComposerUsagePopover.tsx:78`), and it rounds. */
    public fun percent(usedTokens: Int?, windowTokens: Int?): Int? {
        if (usedTokens == null || windowTokens == null || windowTokens <= 0) return null
        return (usedTokens.toDouble() / windowTokens * 100).roundToInt().coerceIn(0, 100)
    }

    /**
     * The denominator. [preset] is the preset this chat is on when the client knows one: the
     * gateway stores a window per preset (`/api/settings` `model_presets[].context_window_tokens`)
     * and that is the last honest answer when no frame said. The chat's own window wins over it,
     * because a chat can run a preset the gateway's configuration does not name — the owner's
     * 200 000 was the `default` preset's window, not `primary`'s 1 000 000 (T37).
     */
    public fun window(thread: ThreadState, settings: SettingsState, preset: String?): Int? =
        thread.contextWindowTokens
            ?: presetWindow(settings, preset ?: thread.modelPreset)
            ?: settings.contextWindowTokens

    private fun presetWindow(settings: SettingsState, preset: String?): Int? {
        if (preset.isNullOrBlank()) return null
        return settings.modelPresets.firstOrNull { it.name == preset }?.contextWindowTokens
    }
}
