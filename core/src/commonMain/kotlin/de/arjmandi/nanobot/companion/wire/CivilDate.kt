package de.arjmandi.nanobot.companion.wire

/**
 * A civil date as a count of days since 1970-01-01, and back. The repo has no date library (D4):
 * the session rows already parse ISO-8601 by hand, and the usage strip needs the one thing a
 * string cannot do — subtract 29 days from today (T44). Both directions are Howard Hinnant's
 * algorithms, which is also what the existing `daysFromCivil` was.
 */
internal object CivilDate {

    /** `2026-11-14` to a day count; null for anything that is not a date. */
    fun epochDay(iso: String): Long? {
        val parts = iso.trim().split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return daysFromCivil(year, month, day)
    }

    /** The inverse: a day count back to `2026-11-14`, zero-padded so it sorts and compares. */
    fun iso(epochDay: Long): String {
        val civil = civilFromDays(epochDay)
        return "${pad(civil.first, 4)}-${pad(civil.second, 2)}-${pad(civil.third, 2)}"
    }

    /** Howard Hinnant's days-from-civil; the civil calendar since 1970-01-01. */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val monthPrime = (month + 9) % 12
        val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    /** ...and its inverse, `civil_from_days`. */
    fun civilFromDays(epochDay: Long): Triple<Int, Int, Int> {
        val shifted = epochDay + 719_468L
        val era = (if (shifted >= 0) shifted else shifted - 146_096L) / 146_097L
        val dayOfEra = shifted - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthPrime = (5L * dayOfYear + 2) / 153
        val day = dayOfYear - (153L * monthPrime + 2) / 5 + 1
        val month = if (monthPrime < 10) monthPrime + 3 else monthPrime - 9
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
        return Triple(year.toInt(), month.toInt(), day.toInt())
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
