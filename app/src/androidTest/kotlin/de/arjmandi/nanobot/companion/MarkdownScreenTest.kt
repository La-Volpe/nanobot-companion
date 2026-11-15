package de.arjmandi.nanobot.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.arjmandi.nanobot.companion.composer.ComposerDriving
import de.arjmandi.nanobot.companion.composer.ComposerViewModel
import de.arjmandi.nanobot.companion.composer.PaletteSheet
import de.arjmandi.nanobot.companion.composer.WorkspaceAccess
import de.arjmandi.nanobot.companion.markdown.MarkdownBlocks
import de.arjmandi.nanobot.companion.markdown.MarkdownView
import de.arjmandi.nanobot.companion.markdown.MdBlock
import de.arjmandi.nanobot.companion.markdown.MdInline
import de.arjmandi.nanobot.companion.markdown.MdListItem
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A5's markdown rendering (the Compose half): prose, bullets, a code fence with its language chip,
 * line numbers on a multiline block and Copy's 1.5 s confirmation, and a table that scrolls inside
 * its own box. The parse is the core's (`MarkdownTest`); this is what only a composition proves.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private object NoComposer : ComposerDriving {
        override suspend fun sendPrompt(text: String): Boolean = false
        override fun enqueue(text: String) = Unit
        override suspend fun stop() = Unit
        override suspend fun sendQueuedNow() = Unit
        override fun discardQueued() = Unit
        override suspend fun setModelPreset(preset: String): Boolean = true
        override suspend fun setWorkspaceScope(access: WorkspaceAccess) = Unit
        override suspend fun loadPalette(text: String, cursor: Int): PaletteSheet? = null
    }

    private fun screen(
        view: MarkdownView,
        composer: ComposerViewModel = ComposerViewModel(NoComposer),
        streaming: Boolean = false,
    ) {
        compose.setContent {
            NanobotTheme {
                MarkdownBlocks(
                    blocks = view.blocks,
                    idPrefix = "t1",
                    isCopied = composer::isCopied,
                    onCopy = composer::copy,
                    streaming = streaming,
                )
            }
        }
    }

    @Test
    fun proseAndBulletsAreDrawnAsText() {
        screen(
            MarkdownView(
                listOf(
                    MdBlock.Paragraph(listOf(MdInline.Text("The gateway keeps one page of 120 turns."))),
                    MdBlock.Bullets(
                        ordered = false,
                        start = 1,
                        items = listOf(
                            MdListItem(0, listOf(MdInline.Text("first item"))),
                            MdListItem(1, listOf(MdInline.Text("nested item"))),
                        ),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("The gateway keeps one page of 120 turns.").assertIsDisplayed()
        compose.onNodeWithText("first item").assertIsDisplayed()
        compose.onNodeWithText("nested item").assertIsDisplayed()
    }

    @Test
    fun aCodeFenceCarriesItsLanguageAndItsLines() {
        screen(
            MarkdownView(
                listOf(
                    MdBlock.Code(
                        language = "kotlin",
                        code = "val a = 1\nval b = 2\nval c = 3",
                        closed = true,
                    ),
                ),
            ),
        )

        compose.onNodeWithTag("markdown.code").assertIsDisplayed()
        compose.onNodeWithText("kotlin").assertIsDisplayed()
        assertEquals(
            3,
            compose.onAllNodesWithTag("markdown.code.lineNumber").fetchSemanticsNodes().size,
            "a multiline block is numbered, one number per line",
        )
    }

    @Test
    fun aSingleLineFenceIsNotNumbered() {
        screen(MarkdownView(listOf(MdBlock.Code(language = null, code = "nanobot", closed = true))))

        compose.onNodeWithText("code").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithTag("markdown.code.lineNumber").fetchSemanticsNodes().size,
            "one line needs no number",
        )
    }

    @Test
    fun copyConfirmsAndThenGoesBack() {
        // The confirmation waits on a gate the test holds, so "Copied" is asserted rather than
        // slept through; releasing it proves the label returns.
        val gate = CompletableDeferred<Unit>()
        val composer = ComposerViewModel(NoComposer, sleep = { gate.await() }, scope = CoroutineScope(Dispatchers.Unconfined))
        screen(MarkdownView(listOf(MdBlock.Code(language = "kotlin", code = "val a = 1", closed = true))), composer)

        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()

        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertIsDisplayed()
    }

    @Test
    fun aTaskItemDrawsItsBoxAndStrikethroughKeepsItsLine() {
        screen(
            MarkdownView(
                listOf(
                    MdBlock.Bullets(
                        ordered = false,
                        start = 1,
                        items = listOf(
                            MdListItem(0, listOf(MdInline.Text("shipped")), checked = true),
                            MdListItem(0, listOf(MdInline.Text("pending")), checked = false),
                        ),
                    ),
                    MdBlock.Paragraph(listOf(MdInline.Strikethrough(listOf(MdInline.Text("gone"))))),
                ),
            ),
        )

        compose.onNodeWithTag("markdown.task.done").assertIsDisplayed()
        compose.onNodeWithTag("markdown.task.open").assertIsDisplayed()
        compose.onNodeWithText("gone").assertIsDisplayed()
    }

    @Test
    fun aDiagramIsItsSourceAndCanBeCopiedWhileTheTurnRuns() {
        // A mermaid fence is the diagram block now (owner's decision, 2026-11-15), and the half it
        // shows is the core's: source while the turn runs. Copy is there before the turn ends,
        // because the source is worth taking as it arrives. `DiagramScreenTest` covers the picture.
        screen(
            MarkdownView(
                listOf(MdBlock.Code(language = "mermaid", code = "graph TD\n  A-->B", closed = false)),
            ),
            streaming = true,
        )

        compose.onNodeWithText("mermaid").assertIsDisplayed()
        compose.onNodeWithText("  A-->B").assertExists()
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithText("Source").assertIsDisplayed()
    }

    @Test
    fun aTableScrollsInsideItsOwnBox() {
        screen(
            MarkdownView(
                listOf(
                    MdBlock.Table(
                        header = listOf(listOf(MdInline.Text("limit")), listOf(MdInline.Text("value"))),
                        alignments = listOf(de.arjmandi.nanobot.companion.markdown.MdAlignment.Left, de.arjmandi.nanobot.companion.markdown.MdAlignment.Left),
                        rows = listOf(
                            listOf(listOf(MdInline.Text("files")), listOf(MdInline.Text("4"))),
                            listOf(listOf(MdInline.Text("per file")), listOf(MdInline.Text("6 MiB"))),
                        ),
                    ),
                ),
            ),
        )

        compose.onNodeWithTag("markdown.table").assertIsDisplayed()
        compose.onNodeWithText("per file").assertIsDisplayed()
        compose.onNodeWithText("6 MiB").assertIsDisplayed()
    }
}
