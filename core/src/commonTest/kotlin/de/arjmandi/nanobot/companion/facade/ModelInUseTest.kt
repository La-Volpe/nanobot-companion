package de.arjmandi.nanobot.companion.facade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T45's core half. The precedence that picks the window is `ContextUsageTest`'s and the token copy
 * is `TokenCountTest`'s; what is pinned here is that Overview's row says what the chip says — the
 * chat's preset and the chat's window — and that a window nobody reported is a blank, not a zero.
 */
class ModelInUseTest {

    @Test
    fun theWindowReadsLikeTheChipsCopy() {
        assertEquals(
            "1M",
            ModelInUse("glm-5.3-flash:cloud", "primary", 1_000_000).windowLabel,
            "the owner's gateway answers 1 000 000 and the row read 1000k (T36)",
        )
        assertEquals("200K", ModelInUse("anthropic/claude-opus-4-5", "default", 200_000).windowLabel)
        assertEquals("32.8K", ModelInUse("deepseek-v4.1-flash:cloud", "primary", 32_768).windowLabel)
    }

    @Test
    fun aWindowNobodyReportedIsNoLabelRatherThanZero() {
        assertNull(
            ModelInUse("gemma4:31b-cloud", "Vision and OCR", null).windowLabel,
            "a compacted page can carry the model and not the window: no denominator, no row",
        )
    }

    @Test
    fun thePresetNamesItselfOnlyWhenTheGatewayDid() {
        assertEquals("Preset primary", ModelInUse("m", "primary", 1_000_000).presetLabel)
        assertNull(ModelInUse("m", null, 1_000_000).presetLabel)
        assertNull(ModelInUse("m", "  ", 1_000_000).presetLabel, "a blank preset is the gateway saying nothing")
    }
}
