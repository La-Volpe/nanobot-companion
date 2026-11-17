package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.SessionRow

/**
 * The drawer's state. The rows themselves come from `wire`, because they
 * are the gateway's payload; sorting and parsing never belong to a view.
 */
public data class SessionListState(
    public val rows: List<SessionRow> = emptyList(),
    public val loaded: Boolean = false,
    /**
     * The last read did not answer. Kept apart from [loaded] because the drawer says two different
     * sentences: "no sessions yet" is a fact about the gateway, and a refused read is not.
     */
    public val failed: Boolean = false,
) {
    /** The gateway really has no sessions: a read answered, and it answered with nothing. */
    public val isEmpty: Boolean get() = loaded && !failed && rows.isEmpty()
}
