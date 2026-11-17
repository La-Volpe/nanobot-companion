package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.wire.UsageDay
import de.arjmandi.nanobot.companion.wire.UsageTotals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Overview draws the usage it already fetched. The window, the gap-filling and the headline are
 * the WebUI's (`settings/TokenUsageCard.tsx:56`), done once here so both phones draw the same
 * chart from the same two numbers, and so a day the gateway never reported is a zero bar rather
 * than a missing one.
 */
class UsageWindowTest {

    private fun usage(vararg days: Pair<String, Long>) =
        UsageTotals(days = days.map { (date, tokens) -> UsageDay(date = date, totalTokens = tokens) })

    @Test
    fun theStripIsThirtyDaysEndingToday() {
        val summary = UsageWindow.last30(usage("2026-11-14" to 505_134L), todayIso = "2026-11-14")

        assertEquals(30, summary.days.size)
        assertEquals("2026-10-16", summary.days.first().date, "the window is 29 days back, today inclusive")
        assertEquals("2026-11-14", summary.days.last().date)
        assertEquals(505_134L, summary.totalTokens)
        assertEquals("505K", summary.headline, "the WebUI's compact copy: 505 134 is 505K")
    }

    @Test
    fun aDayTheGatewayDidNotReportIsAZeroBarRatherThanAMissingOne() {
        val summary = UsageWindow.last30(
            usage("2026-10-20" to 1_000L, "2026-11-14" to 2_000L),
            todayIso = "2026-11-14",
        )

        assertEquals(30, summary.days.size, "every day of the window is a slot")
        assertEquals(0L, summary.days.first().tokens, "the strip starts 29 days back, empty")
        assertEquals(1_000L, summary.days.first { it.date == "2026-10-20" }.tokens)
        assertEquals(3_000L, summary.totalTokens, "and the headline is the sum of the slots, not of the payload's totals")
    }

    @Test
    fun aDayOutsideTheWindowIsNotInIt() {
        val summary = UsageWindow.last30(
            usage("2026-01-01" to 9_000L, "2026-11-14" to 2_000L),
            todayIso = "2026-11-14",
        )

        assertEquals(2_000L, summary.totalTokens, "a year-old day is not in the last 30")
        assertEquals(2_000L, summary.peakTokens, "and the peak is the window's own, not the year's")
    }

    @Test
    fun theBarsAreScaledToTheWindowsPeak() {
        val summary = UsageWindow.last30(
            usage("2026-11-14" to 1_000L, "2026-11-13" to 250L, "2026-11-12" to 250L),
            todayIso = "2026-11-14",
        )

        assertEquals(1.0, summary.days.last().fraction, "the biggest day is a full bar")
        assertEquals(0.25, summary.days[27].fraction, "and the others are the same share of it")
        assertEquals(1_000L, summary.peakTokens, "the peak is one day, not the window's total")
    }

    @Test
    fun noUsageIsNoChartRatherThanAFlatLineOfLies() {
        val summary = UsageWindow.last30(UsageTotals(), todayIso = "2026-11-14")

        assertFalse(summary.hasUsage)
        assertEquals(0L, summary.totalTokens)
        assertEquals(30, summary.days.size, "the slots are dates, so the axis still says what it would show")
        assertTrue(summary.days.all { it.fraction == 0.0 }, "no bar may claim a share of a peak that is zero")
    }

    @Test
    fun theWindowCrossesAMonthBoundary() {
        val summary = UsageWindow.last30(UsageTotals(), todayIso = "2026-03-01")

        assertEquals("2026-01-31", summary.days.first().date, "30 days back from 1 March is 31 January")
        assertEquals("2026-03-01", summary.days.last().date)
        assertEquals(summary.days.size, summary.days.map { it.date }.toSet().size, "no day twice")
    }

    @Test
    fun aTodayNobodyCanParseEndsTheStripAtTheNewestDayTheGatewayReported() {
        val summary = UsageWindow.last30(usage("2026-11-14" to 2_000L), todayIso = "yesterday")

        assertEquals("2026-11-14", summary.days.last().date)
        assertEquals(2_000L, summary.totalTokens, "a phone with a broken date still draws its own reading")
    }

    /** The card's second line: the requests and the cache rate the design prints under the headline. */
    @Test
    fun theSecondLineIsTheGatewaysOwnCounts() {
        val summary = UsageWindow.last30(
            UsageTotals(days = listOf(UsageDay("2026-11-14", 1_000)), requests30d = 412, cacheHitRate = 0.62),
            todayIso = "2026-11-14",
        )

        assertEquals(412, summary.requests)
        assertEquals(62, summary.cacheHitPercent)
        assertNull(
            UsageWindow.last30(UsageTotals(), todayIso = "2026-11-14").cacheHitPercent,
            "no denominator is no rate, not 0%",
        )
    }

    @Test
    fun aMillionReadsAsTheWebUIWritesIt() {
        assertEquals(
            "1.5M",
            UsageWindow.last30(usage("2026-11-14" to 1_500_000L), todayIso = "2026-11-14").headline,
            "`formatCompactTokenCount`: one decimal below 100M",
        )
    }
}
