package de.arjmandi.nanobot.companion

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.arjmandi.nanobot.companion.markdown.MarkdownBlocks
import de.arjmandi.nanobot.companion.markdown.MarkdownView
import de.arjmandi.nanobot.companion.markdown.MdBlock
import de.arjmandi.nanobot.companion.ui.NanobotTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A mermaid fence on the Android half (owner's decision, 2026-11-15): the picture the bundled
 * renderer draws, the fence's source, and the toggle between them. The renderer, the page and the
 * height measurement are the real ones — a WebView cannot be faked into proving it measured.
 *
 * The screenshots are the evidence the manager needs for a diagram that composes, so the three
 * states write themselves to the app's own files directory (`adb pull` takes them from there).
 */
@RunWith(AndroidJUnit4::class)
class DiagramScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val fence = "graph TD\n  A-->B\n  B-->C"

    private fun screen(streaming: Boolean, code: String = fence) {
        compose.setContent {
            NanobotTheme {
                // The block sits where a chat puts it, inset from the status bar, so the screenshot
                // of the real composition reads as the app's own drawing.
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    MarkdownBlocks(
                        blocks = MarkdownView(
                            listOf(MdBlock.Code(language = "mermaid", code = code, closed = !streaming)),
                        ).blocks,
                        idPrefix = "t1",
                        isCopied = { false },
                        onCopy = {},
                        streaming = streaming,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun await(tag: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun gone(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    /** The whole screen, which is the only way a WebView's own surface reaches a file. */
    private fun save(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screen: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir
        File(directory, "$name.png").outputStream().use { output ->
            screen.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test
    fun aSettledDiagramOpensOnItsPreviewAndFlipsToItsSource() {
        screen(streaming = false)

        await("diagram.preview")
        compose.onNodeWithText("graph TD").assertDoesNotExist()
        save("android-diagram-preview")

        compose.onNodeWithTag("diagram.view.source").performClick()
        await("diagram.source")
        compose.onNodeWithText("graph TD").assertExists()
        gone("diagram.preview")
        save("android-diagram-source")

        compose.onNodeWithTag("diagram.view.preview").performClick()
        await("diagram.preview")
        gone("diagram.source")
    }

    @Test
    fun aStreamingDiagramShowsItsSourceFirst() {
        screen(streaming = true)

        // Source is what the core decides while the turn arrives, and the picture must not be
        // built at all — a half-written fence is not a diagram.
        assertTrue(compose.onAllNodesWithTag("diagram.source").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("graph TD").assertExists()
        assertTrue(compose.onAllNodesWithTag("diagram.preview").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("diagram.view.preview").performClick()
        await("diagram.preview")
        gone("diagram.source")
    }

    @Test
    fun aDiagramThatCannotBeDrawnFallsBackToItsSourceWithOneSentence() {
        screen(streaming = false, code = "graph TD\n  A-->")

        await("diagram.failed")
        compose.onNodeWithText("This diagram could not be drawn, so its source is shown.").assertExists()
        compose.onNodeWithText("graph TD").assertExists()
        assertTrue(compose.onAllNodesWithTag("diagram.preview").fetchSemanticsNodes().isEmpty())
        save("android-diagram-failed")
    }
}
