package de.arjmandi.nanobot.companion.platform

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

public actual fun systemClock(): Clock = object : Clock {
    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
}
