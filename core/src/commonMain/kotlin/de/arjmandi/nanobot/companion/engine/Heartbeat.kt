package de.arjmandi.nanobot.companion.engine

/**
 * How often the engine makes the socket prove it is still there, and how long it waits.
 *
 * 25 s because the gateway already keeps that rhythm itself: it pings every open socket at
 * `ping_interval_s = 20.0` (`nanobot/channels/websocket/runtime.py:195`), so a heartbeat in the
 * same band rides along with a timer the gateway was going to wake for anyway, and a socket that
 * died silently is noticed inside half a minute instead of when the user next types.
 *
 * 8 s because a loopback or LAN round trip is milliseconds: this window is not measuring the
 * gateway, it is allowing for a phone that has just woken its radio and put the question in a
 * queue. Longer than that and the green dot outlives the reason for it.
 */
internal data class HeartbeatPolicy(
    val intervalMs: Long = 25_000,
    val windowMs: Long = 8_000,
)
