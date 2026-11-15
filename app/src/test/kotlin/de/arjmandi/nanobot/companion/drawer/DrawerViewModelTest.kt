package de.arjmandi.nanobot.companion.drawer

import de.arjmandi.nanobot.companion.core.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The drawer's search and its states (plan.md A2). The red line the plan names is the no-match
 * state: a query that matches nothing is a sentence, not an empty list that looks like a broken
 * drawer — and it is not the same sentence as "this gateway has no sessions".
 */
class DrawerViewModelTest {

    private fun row(title: String, preview: String, key: String = title) = SessionSummary(
        key = "websocket:$key",
        chatId = key,
        title = title,
        preview = preview,
        updatedAtMs = 1_000,
    )

    private fun loaded(vararg rows: SessionSummary) = DrawerViewModel(rows = rows.toList(), loaded = true)

    @Test
    fun noMatchState() {
        val model = loaded(
            row("Pong reply", "Reply with exactly the word: pong"),
            row("Context compaction", "Summarise the last ten messages"),
        )

        model.searchText = "websocket"

        assertEquals(DrawerViewModel.ListState.NoMatches("websocket"), model.state)
        assertEquals("No sessions match “websocket”.", model.emptyMessage)
        assertTrue(model.matches.isEmpty(), "the list draws nothing and the sentence explains it")

        // "No rows at all" is a different sentence: the gateway has no sessions, nothing was filtered.
        val emptyGateway = loaded()
        assertEquals(DrawerViewModel.ListState.Empty, emptyGateway.state)
        assertEquals("No sessions yet. Start one from the chat.", emptyGateway.emptyMessage)
    }

    @Test
    fun searchMatchesTheTitleAndThePreviewCaseInsensitively() {
        val model = loaded(
            row("Pong reply", "Reply with exactly the word: pong"),
            row("Context compaction", "Summarise the last ten messages"),
        )

        model.searchText = "PONG"
        assertEquals(listOf("Pong reply"), model.matches.map { it.displayTitle })

        model.searchText = "ten messages"
        assertEquals(listOf("Context compaction"), model.matches.map { it.displayTitle }, "the preview is searched too")
    }

    @Test
    fun anUnloadedDrawerIsLoadingNotEmpty() {
        val model = DrawerViewModel()

        assertEquals(DrawerViewModel.ListState.Loading, model.state)
        assertNull(model.emptyMessage, "nothing has answered yet: a spinner, not a verdict")
    }

    @Test
    fun aFailedReadIsNotAnEmptyDrawer() {
        // `GET /api/sessions` answered with a 500. Before this, the core turned that into
        // `loaded = true` with no rows, and the drawer said "No sessions yet" about a gateway it
        // had never managed to read.
        val model = DrawerViewModel()
        model.update(rows = emptyList(), loaded = true, failed = true)

        assertEquals(DrawerViewModel.ListState.Failed, model.state)
        assertNull(model.emptyMessage, "'no sessions yet' is a claim a failed read cannot make")
        assertEquals("Could not read your sessions from the gateway.", model.failureMessage)
    }

    @Test
    fun aDrawerThatIsLoadingSaysNothingRatherThanReadingAsEmpty() {
        val model = loaded(row("One", "first"))
        model.update(rows = emptyList(), loaded = false)

        assertEquals(DrawerViewModel.ListState.Loading, model.state, "a re-read is a skeleton, not a verdict")
        assertNull(model.emptyMessage)
        assertNull(model.failureMessage)
    }

    @Test
    fun clearingTheSearchBringsEveryRowBack() {
        val model = loaded(row("One", "first"), row("Two", "second"))
        model.searchText = "two"
        assertEquals(1, model.matches.size)

        model.clearSearch()

        assertEquals(listOf("One", "Two"), model.matches.map { it.displayTitle }, "and in the gateway's own order")
        assertTrue(model.state is DrawerViewModel.ListState.Rows)
    }

    @Test
    fun theGatewayOrderIsKept() {
        // The gateway sorts newest first (D15.2); a client that re-sorted by the string timestamp
        // would put them in a different order, so this only asserts that nothing re-sorts.
        val model = loaded(row("Newest", "3"), row("Middle", "2"), row("Oldest", "1"))

        assertEquals(listOf("Newest", "Middle", "Oldest"), model.matches.map { it.displayTitle })
    }

    @Test
    fun anUntitledRowStillHasAName() {
        val model = loaded(row("", "a row the gateway never titled"))

        assertEquals("Untitled session", model.matches.single().displayTitle)
        model.searchText = "untitled"
        assertEquals(1, model.matches.size, "and search matches that name, because it is what is on screen")
    }

    @Test
    fun theArchivedRowIsARememberedToggle() {
        val model = loaded(row("One", "first"))

        assertEquals(false, model.showsArchived, "the archived row is closed until it is asked for")
        model.toggleArchived()
        assertEquals(true, model.showsArchived)
        assertEquals(DrawerViewModel.ListState.Rows(model.rows), model.state, "opening it filters nothing")
    }
}
