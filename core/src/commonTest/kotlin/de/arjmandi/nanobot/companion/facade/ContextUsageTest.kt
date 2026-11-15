package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.wire.SettingsState
import de.arjmandi.nanobot.companion.wire.ModelPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ring's two numbers, and the precedence that picks the denominator (T37). The WebUI's own
 * arithmetic is `Math.min(100, Math.round(tokens / window * 100))`
 * (`thread/ComposerUsagePopover.tsx:78`), and its order for the window is
 * `thread/ContextInspector.tsx:52`.
 */
class ContextUsageTest {

    private val settings = SettingsState(
        contextWindowTokens = 1_000_000,
        modelPresets = listOf(
            ModelPreset(name = "default", model = "m", provider = "p", contextWindowTokens = 200_000, isDefault = true),
            ModelPreset(name = "primary", model = "m", provider = "p", contextWindowTokens = 1_000_000, active = true),
        ),
    )

    @Test
    fun percentRoundsLikeTheBrowser() {
        assertEquals(71, ContextUsage.percent(usedTokens = 141_510, windowTokens = 200_000), "70.75 rounds to 71")
        assertEquals(14, ContextUsage.percent(usedTokens = 141_510, windowTokens = 1_000_000), "14.15 rounds to 14")
        assertEquals(1, ContextUsage.percent(usedTokens = 10, windowTokens = 2_000), "0.5 rounds up")
        assertEquals(100, ContextUsage.percent(usedTokens = 999_999, windowTokens = 200_000), "a ring never overflows")
        assertEquals(0, ContextUsage.percent(usedTokens = 0, windowTokens = 200_000), "an empty context is 0%, not nothing")
    }

    @Test
    fun noWindowOrNoUsageIsNoRing() {
        assertNull(ContextUsage.percent(usedTokens = null, windowTokens = 200_000))
        assertNull(ContextUsage.percent(usedTokens = 10, windowTokens = null))
        assertNull(ContextUsage.percent(usedTokens = 10, windowTokens = 0), "a window of zero is no window")
    }

    @Test
    fun theThreadsOwnWindowWinsThenThePresetThenTheGateway() {
        val own = ThreadState(chatId = "websocket:a", contextWindowTokens = 32_768)
        assertEquals(32_768, ContextUsage.window(own, settings, preset = "primary"), "a frame that carried a window is the chat's answer")

        val silent = ThreadState(chatId = "websocket:a", modelPreset = "default")
        assertEquals(200_000, ContextUsage.window(silent, settings, preset = null), "the chat's own preset names its window")
        assertEquals(200_000, ContextUsage.window(ThreadState(chatId = "websocket:a"), settings, preset = "default"), "the preset the client picked, when the thread has not said")

        val unknown = ThreadState(chatId = "websocket:a")
        assertEquals(1_000_000, ContextUsage.window(unknown, settings, preset = "nope"), "a preset the gateway does not list falls to the gateway's window")
        assertNull(ContextUsage.window(unknown, SettingsState(), preset = null), "and a gateway that said nothing leaves no denominator to invent")
    }
}
