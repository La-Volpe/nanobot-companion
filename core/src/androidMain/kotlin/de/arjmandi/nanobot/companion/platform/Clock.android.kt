package de.arjmandi.nanobot.companion.platform

public actual fun systemClock(): Clock = object : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
