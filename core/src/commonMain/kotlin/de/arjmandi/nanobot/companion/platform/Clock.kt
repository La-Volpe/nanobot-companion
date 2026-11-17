package de.arjmandi.nanobot.companion.platform

/**
 * Wall-clock time, injected. Nothing in `projection`, `wire` or `engine`
 * calls the platform clock directly: reducers take `nowMs` as a parameter so the same
 * scripted turn reduces identically in a test and on a device.
 */
public interface Clock {
    public fun nowMs(): Long
}

public expect fun systemClock(): Clock
