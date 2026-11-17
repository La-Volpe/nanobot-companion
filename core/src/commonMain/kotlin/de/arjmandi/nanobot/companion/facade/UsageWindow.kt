package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.wire.CivilDate
import de.arjmandi.nanobot.companion.wire.UsageTotals
import kotlin.math.roundToInt

/** One bar of Overview's daily strip: the day, its tokens, and its height already divided. */
public data class UsageBar(
    public val date: String,
    public val tokens: Long,
    /** `tokens / peak`, 0 to 1. Zero for a day with no usage, and for every bar of an empty window. */
    public val fraction: Double,
)

/**
 * Overview's token card, as one value: the headline the WebUI prints and the 30 days behind
 * it. Nothing here is invented: a day the gateway did not report is a zero bar, and a window
 * with no usage at all says so instead of drawing a chart of nothing.
 */
public data class UsageSummary(
    /** Oldest first, always [UsageWindow.DAYS] of them. */
    public val days: List<UsageBar>,
    public val totalTokens: Long,
    public val peakTokens: Long,
    /** The gateway's own 30-day request count, which is what the card's second line names. */
    public val requests: Int = 0,
    /** 0..100, rounded; null when the gateway had no denominator, which is not "0%". */
    public val cacheHitPercent: Int? = null,
) {

    /** The headline in the WebUI's compact copy (`TokenUsageCard.tsx:69`): `505K`, `1.5M`. */
    public val headline: String get() = TokenCount.compact(totalTokens)

    public val hasUsage: Boolean get() = totalTokens > 0
}

/**
 * The card's window: the last 30 days ending today, in the WebUI's arithmetic
 * (`settings/TokenUsageCard.tsx:56` builds the same 30 slots, fills the gaps with zero and sums
 * the slot totals for the headline, not the payload's flat `total_tokens_30d`, so the number
 * above the chart is the chart).
 *
 * `today` is the platform's own civil date: the reader is holding the phone, so its date is the
 * one that ends the strip. The core does no clock and no zone arithmetic; subtraction on a day
 * count is all this needs (`CivilDate`).
 */
public object UsageWindow {

    public const val DAYS: Int = 30

    /** The card's "62%": the rate as the reader reads it, one decimal only when it has one. */
    private fun percent(rate: Double): Int = (rate * 100).roundToInt().coerceIn(0, 100)

    public fun last30(usage: UsageTotals?, todayIso: String): UsageSummary {
        val days = usage?.days.orEmpty()
        val anchor = CivilDate.epochDay(todayIso)
            // A date nobody can parse is still a date to answer: the newest day the gateway
            // reported ends the strip rather than printing an empty chart over a real reading.
            ?: days.mapNotNull { CivilDate.epochDay(it.date) }.maxOrNull()
            ?: return UsageSummary(days = emptyList(), totalTokens = 0, peakTokens = 0)

        val byDate = days.associate { it.date to it.totalTokens }
        val slots = (0 until DAYS).map { offset ->
            val date = CivilDate.iso(anchor - (DAYS - 1 - offset))
            date to (byDate[date] ?: 0L)
        }
        val peak = slots.maxOfOrNull { it.second } ?: 0L
        return UsageSummary(
            days = slots.map { (date, tokens) ->
                UsageBar(
                    date = date,
                    tokens = tokens,
                    // A bar for a window with no peak is a bar of nothing, not a division by zero.
                    fraction = if (peak <= 0) 0.0 else tokens.toDouble() / peak.toDouble(),
                )
            },
            totalTokens = slots.sumOf { it.second },
            peakTokens = peak,
            requests = usage?.requests30d ?: 0,
            cacheHitPercent = usage?.cacheHitRate?.let { percent(it) },
        )
    }
}
