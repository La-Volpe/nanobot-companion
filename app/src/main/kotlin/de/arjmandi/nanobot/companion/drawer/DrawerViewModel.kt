package de.arjmandi.nanobot.companion.drawer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.arjmandi.nanobot.companion.core.SessionSummary

/**
 * The drawer's rows and its four states (plan.md A2, design artifact `data-view="drawer"`, the
 * Android twin of iOS's `DrawerViewModel`). The gateway hands the rows down; what a search field
 * does to them, and what the drawer says when there is nothing to show, is a decision worth
 * testing — so it lives here, not in a composable.
 */
class DrawerViewModel(rows: List<SessionSummary> = emptyList(), loaded: Boolean = false) {

    sealed interface ListState {
        /** Nothing has arrived yet: a spinner, not "no sessions". */
        data object Loading : ListState

        /** Loaded with no rows at all: the drawer has never had a chat. */
        data object Empty : ListState

        /** Loaded, rows exist, the query matched none of them. */
        data class NoMatches(val query: String) : ListState

        data class Rows(val rows: List<SessionSummary>) : ListState
    }

    var rows by mutableStateOf(rows)
        private set

    var loaded by mutableStateOf(loaded)
        private set

    var searchText by mutableStateOf("")

    var showsArchived by mutableStateOf(false)
        private set

    fun update(rows: List<SessionSummary>, loaded: Boolean) {
        this.rows = rows
        this.loaded = loaded
    }

    fun toggleArchived() {
        showsArchived = !showsArchived
    }

    fun clearSearch() {
        searchText = ""
    }

    val isSearching: Boolean get() = searchText.isNotEmpty()

    /**
     * Search is a case-insensitive substring over what a row shows — title and preview — which is
     * what the WebUI searches in the sessions it has already loaded. The gateway sorts the rows;
     * this does not re-sort by a string timestamp (D15.2).
     */
    val matches: List<SessionSummary>
        get() {
            if (!isSearching) return rows
            val needle = searchText.lowercase()
            return rows.filter { row ->
                row.displayTitle.lowercase().contains(needle) || row.preview.lowercase().contains(needle)
            }
        }

    val state: ListState
        get() = when {
            !loaded -> ListState.Loading
            rows.isEmpty() -> ListState.Empty
            isSearching && matches.isEmpty() -> ListState.NoMatches(searchText)
            else -> ListState.Rows(matches)
        }

    /** What the empty and no-match lines say. The archived row and Settings stay visible in every state: they are not sessions. */
    val emptyMessage: String?
        get() = when (val current = state) {
            ListState.Loading, is ListState.Rows -> null
            ListState.Empty -> "No sessions yet. Start one from the chat."
            is ListState.NoMatches -> "No sessions match “${current.query}”."
        }
}
