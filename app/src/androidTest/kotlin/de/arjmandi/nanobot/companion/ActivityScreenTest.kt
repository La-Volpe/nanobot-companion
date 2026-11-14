package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.activity.ActivityCluster
import de.arjmandi.nanobot.companion.activity.ActivityClusterRow
import de.arjmandi.nanobot.companion.activity.ActivityHeadline
import de.arjmandi.nanobot.companion.activity.ActivityItemView
import de.arjmandi.nanobot.companion.activity.ActivityKind
import de.arjmandi.nanobot.companion.activity.ActivityStatus
import de.arjmandi.nanobot.companion.activity.ActivityViewModel
import de.arjmandi.nanobot.companion.activity.ToolRunStatus
import de.arjmandi.nanobot.companion.chat.ChatTurnView
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A4's rendering (the Compose half): the headline sentence is built from the design's clauses, a
 * failure shows the gateway's reason, a tap opens what the fold hid, and a diff keeps its markers.
 * The fold rules themselves are `ActivityViewModelTest`'s; this is what only a composition proves.
 */
@RunWith(AndroidJUnit4::class)
class ActivityScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun cluster(
        status: ActivityStatus = ActivityStatus.Done,
        headline: ActivityHeadline = ActivityHeadline.Finished(seconds = 8, steps = 3, files = 1),
        items: List<ActivityItemView> = listOf(
            ActivityItemView("tool:1", ActivityKind.Tool("run_command", "pytest -q", ToolRunStatus.Done, "exit 0")),
        ),
        isOpen: Boolean = true,
        hasFailure: Boolean = false,
    ) = ActivityCluster(
        turnId = "t1",
        status = status,
        headline = headline,
        items = items,
        isOpen = isOpen,
        hasFailure = hasFailure,
        steps = items.size,
    )

    private fun screen(cluster: ActivityCluster, onToggle: () -> Unit = {}) {
        compose.setContent {
            NanobotTheme { ActivityClusterRow(cluster = cluster, onToggle = onToggle) }
        }
    }

    @Test
    fun theHeadlineIsTheDesignsSentence() {
        screen(cluster())

        compose.onNodeWithText("Thought for 8s · 3 steps · 1 file changed").assertIsDisplayed()
        compose.onNodeWithText("done").assertIsDisplayed()
    }

    @Test
    fun aRunningTurnSaysWhatItIsDoing() {
        screen(cluster(status = ActivityStatus.Running, headline = ActivityHeadline.Thinking))

        compose.onNodeWithText("Thinking").assertIsDisplayed()
        compose.onNodeWithText("running").assertIsDisplayed()
    }

    @Test
    fun aFailedToolShowsTheGatewaysReason() {
        screen(
            cluster(
                status = ActivityStatus.Failed,
                hasFailure = true,
                items = listOf(
                    ActivityItemView("tool:1", ActivityKind.Tool("run_command", "pytest -q", ToolRunStatus.Failed, "exit 4")),
                ),
            ),
        )

        compose.onNodeWithText("failed").assertIsDisplayed()
        compose.onNodeWithText("exit 4").assertIsDisplayed()
    }

    @Test
    fun theFoldHidesTheBody() {
        screen(cluster(isOpen = false))

        compose.onNodeWithTag("activity.tool").assertDoesNotExist()
    }

    @Test
    fun theHeadIsTheTap() {
        var toggles = 0
        screen(cluster(isOpen = false), onToggle = { toggles += 1 })

        compose.onNodeWithTag("activity.head").performClick()
        assertEquals(1, toggles, "the head is the tap, and it asks the model to toggle")
    }

    @Test
    fun anOpenClusterShowsItsRows() {
        screen(cluster(isOpen = true))

        compose.onNodeWithTag("activity.tool").assertIsDisplayed()
    }

    @Test
    fun aFileEditKeepsItsDiffMarkers() {
        screen(
            cluster(
                items = listOf(
                    ActivityItemView(
                        "file:1",
                        ActivityKind.FileEdit(
                            path = "tool-probe.txt",
                            status = ToolRunStatus.Done,
                            added = 1,
                            deleted = 0,
                            operation = "create",
                            diff = "--- tool-probe.txt\n+++ tool-probe.txt\n@@ -0,0 +1 @@\n+nanobot",
                        ),
                    ),
                ),
            ),
        )

        compose.onNodeWithTag("activity.diff").assertIsDisplayed()
        compose.onNodeWithText("@@ -0,0 +1 @@").assertIsDisplayed()
        compose.onNodeWithText("+nanobot").assertIsDisplayed()
        compose.onNodeWithText("+1").assertIsDisplayed()
    }

    /**
     * The whole path, through the model: a thread with activity gets a cluster, and the composable
     * draws the model's own headline rather than one of its own.
     */
    @Test
    fun theThreadHandsTheModelItsClusters() {
        val model = ActivityViewModel(now = { 15_000 })
        model.update(
            listOf(
                ChatTurnView(
                    id = "t1",
                    prompt = "q",
                    promptPending = false,
                    answer = "",
                    answerStreaming = false,
                    running = false,
                    activity = listOf(ActivityItemView("tool:1", ActivityKind.Tool("run_command", "pytest -q", ToolRunStatus.Done, null))),
                    startedAtMs = 7_000,
                ),
            ),
        )
        val cluster = model.cluster("t1")!!
        compose.setContent { NanobotTheme { ActivityClusterRow(cluster = cluster, onToggle = {}) } }

        compose.onNodeWithText("Thought for 8s · 1 step").assertIsDisplayed()
    }
}
