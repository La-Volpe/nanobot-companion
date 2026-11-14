package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.core.SessionSummary
import de.arjmandi.nanobot.companion.drawer.DrawerContent
import de.arjmandi.nanobot.companion.drawer.DrawerViewModel
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The drawer's taps (plan.md A2's Compose half): search filters to the row it matches, a query
 * nothing matches says so instead of showing an empty list, the pinned entries survive that state,
 * and picking a row is one command that closes the drawer. The rules are
 * `DrawerViewModelTest`'s; this is what only a composition can prove.
 */
@RunWith(AndroidJUnit4::class)
class DrawerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var selected: SessionSummary? = null
    private var settingsOpened: Int = 0

    private fun sample() = listOf(
        SessionSummary("websocket:a", "a", "Pong reply", "Reply with exactly the word: pong", 3_000),
        SessionSummary("websocket:b", "b", "Context compaction", "Summarise the last ten messages", 2_000),
    )

    private fun drawer(rows: List<SessionSummary> = sample(), loaded: Boolean = true) {
        val model = DrawerViewModel(rows, loaded)
        compose.setContent {
            NanobotTheme {
                DrawerContent(
                    model = model,
                    selectedKey = null,
                    onSelect = { selected = it },
                    onNewChat = {},
                    onAutomations = {},
                    onSettings = { settingsOpened += 1 },
                )
            }
        }
    }

    @Test
    fun theDrawerListsTheGatewaysSessionsByTheirTitle() {
        drawer()

        compose.onNodeWithText("Pong reply").assertIsDisplayed()
        compose.onNodeWithText("Context compaction").assertIsDisplayed()
    }

    @Test
    fun searchFiltersToTheRowItMatches() {
        drawer()

        compose.onNodeWithTag("drawer.search").performTextInput("compaction")

        compose.onNodeWithText("Context compaction").assertIsDisplayed()
        compose.onNodeWithTag("drawer.row.websocket:a").assertDoesNotExist()
    }

    @Test
    fun aQueryThatMatchesNothingSaysSoAndKeepsThePinnedEntries() {
        drawer()

        compose.onNodeWithTag("drawer.search").performTextInput("zzzz")

        compose.onNodeWithText("No sessions match “zzzz”.").assertIsDisplayed()
        compose.onNodeWithTag("drawer.archived").assertIsDisplayed()
        compose.onNodeWithTag("drawer.automations").assertIsDisplayed()
        compose.onNodeWithTag("drawer.settings").assertIsDisplayed()
    }

    @Test
    fun anEmptyGatewayIsNotANoMatchState() {
        drawer(rows = emptyList(), loaded = true)

        compose.onNodeWithText("No sessions yet. Start one from the chat.").assertIsDisplayed()
    }

    @Test
    fun pickingARowIsOneCommand() {
        drawer()

        compose.onNodeWithTag("drawer.row.websocket:a").performClick()

        assert(selected?.key == "websocket:a") { "the drawer tells the shell which chat to open" }
    }

    @Test
    fun settingsIsPinnedAtTheBottom() {
        drawer()

        compose.onNodeWithTag("drawer.settings").performClick()

        assert(settingsOpened == 1)
    }
}
