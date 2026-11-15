package de.arjmandi.nanobot.companion.facade

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The WebUI's token copy (T36), pinned at the sizes the owner's gateway actually answers: the
 * model sheet lists `default` at 200 000, `primary` at 1 000 000 and `Vision and OCR` at 256 000,
 * and the Overview row printed `1000k tokens` for a million until this existed.
 *
 * The expected strings are `webui/src/lib/model-context-format.ts` and `lib/format.ts` read
 * literally, not measured from this code.
 */
class TokenCountTest {

    @Test
    fun aModelWindowUsesTheWebuisExactMaps() {
        assertEquals("64K", TokenCount.modelWindow(65_536), "65 536 would otherwise print as 65.5K")
        assertEquals("256K", TokenCount.modelWindow(262_144))
        assertEquals("1M", TokenCount.modelWindow(1_048_576), "a power-of-two million is still one million")
    }

    @Test
    fun aWindowRendersInMillionsOnceItIsOne() {
        assertEquals("1M", TokenCount.modelWindow(1_000_000), "the owner's primary preset: 1M, never 1000k")
        assertEquals("200K", TokenCount.modelWindow(200_000))
        assertEquals("999", TokenCount.modelWindow(999), "below a thousand is the number itself")
        assertEquals("32.8K", TokenCount.modelWindow(32_768), "the dev gateway's window, one decimal")
    }

    /** `formatContextWindow` keeps the decimal `toFixed(1)` produces; the model map is what fixes it. */
    @Test
    fun aRawWindowKeepsTheDecimalTheWebuiKeeps() {
        assertEquals("1.0M", TokenCount.window(1_048_576), "formatContextWindow itself says 1.0M here")
        assertEquals("1M", TokenCount.window(1_000_000))
        assertEquals("32.8K", TokenCount.window(32_768))
        assertEquals("999", TokenCount.window(999))
    }

    @Test
    fun aRunningCountIsCompactBelowOneHundredThousand() {
        assertEquals("999", TokenCount.compact(999))
        assertEquals("15.5K", TokenCount.compact(15_500), "one decimal while it means something")
        assertEquals("142K", TokenCount.compact(141_510), "the owner's context usage: no decimal from 100k up")
        assertEquals("155K", TokenCount.compact(155_000), "no decimal from 100k up")
        assertEquals("1.5M", TokenCount.compact(1_500_000))
        assertEquals("1M", TokenCount.compact(1_000_000), "a whole million drops the .0")
    }
}
