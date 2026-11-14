package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.SessionRow

/**
 * The drawer's state (plan.md C5.1). The rows themselves come from `wire`, because they
 * are the gateway's payload; sorting and parsing never belong to a view.
 */
public data class SessionListState(
    public val rows: List<SessionRow> = emptyList(),
    public val loaded: Boolean = false,
) {
    public val isEmpty: Boolean get() = loaded && rows.isEmpty()
}
