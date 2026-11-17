package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.CivilDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The day count the usage strip subtracts on. It is Howard Hinnant's pair of algorithms, the
 * same one the session rows' parser already used; what matters here is that the two directions are
 * inverses across a month, a year and a leap day, because "30 days back from today" is the whole
 * arithmetic the chart does.
 */
class CivilDateTest {

    @Test
    fun theEpochIsDayZero() {
        assertEquals(0L, CivilDate.epochDay("1970-01-01"))
        assertEquals("1970-01-01", CivilDate.iso(0))
        assertEquals(10_957L, CivilDate.epochDay("2000-01-01"), "a known day count")
    }

    @Test
    fun theTwoDirectionsAreInverses() {
        for (date in listOf("2026-11-14", "2026-01-31", "2026-03-01", "2024-02-29", "1999-12-31", "2100-03-01")) {
            assertEquals(date, CivilDate.iso(CivilDate.epochDay(date)!!), "round trip of $date")
        }
    }

    @Test
    fun aLeapDayIsADay() {
        assertEquals(
            1L,
            CivilDate.epochDay("2024-03-01")!! - CivilDate.epochDay("2024-02-29")!!,
            "29 February 2024 is one day before 1 March",
        )
        assertEquals(
            1L,
            CivilDate.epochDay("2023-03-01")!! - CivilDate.epochDay("2023-02-28")!!,
            "and a year without one goes straight to March",
        )
    }

    @Test
    fun anythingThatIsNotADateIsNull() {
        assertNull(CivilDate.epochDay("yesterday"))
        assertNull(CivilDate.epochDay("2026-13-01"), "an impossible month")
        assertNull(CivilDate.epochDay("2026-11"), "a date with no day")
        assertNull(CivilDate.epochDay(""))
    }
}
