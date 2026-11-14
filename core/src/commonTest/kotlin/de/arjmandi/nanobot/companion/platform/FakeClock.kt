package de.arjmandi.nanobot.companion.platform

/**
 * The clock every reducer and engine test drives. Engine scenarios need to step time
 * by hand and assert on what happened in between (queue flush, notice expiry, retry
 * countdown), which the wall clock cannot offer.
 */
public class FakeClock(startMs: Long = 0) : Clock {

    private var current: Long = startMs

    override fun nowMs(): Long = current

    public fun setTo(ms: Long) {
        current = ms
    }

    public fun advanceBy(ms: Long) {
        current += ms
    }
}
