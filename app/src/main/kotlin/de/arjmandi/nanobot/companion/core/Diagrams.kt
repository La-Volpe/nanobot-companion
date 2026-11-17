package de.arjmandi.nanobot.companion.core

import de.arjmandi.nanobot.companion.facade.DIAGRAM_ASSET_NAME
import de.arjmandi.nanobot.companion.facade.DiagramView
import de.arjmandi.nanobot.companion.facade.diagramDocument
import de.arjmandi.nanobot.companion.facade.diagramView
import de.arjmandi.nanobot.companion.facade.isDiagramLanguage
import de.arjmandi.nanobot.companion.markdown.DiagramMode

/**
 * The diagram rules, which are the core's (`MarkdownDiagrams.kt`), in the app's own names.
 *
 * `MarkdownBlocks` draws `MdBlock`s and never a facade type, so the one file that translates
 * between the two is where the question is asked: which half of a diagram a block shows (source
 * while the turn arrives, the picture once it has ended, source when the renderer refuses it), and
 * what the WebView loads. One implementation, in the core, called through here.
 */
internal object Diagrams {

    fun isDiagram(language: String?): Boolean = isDiagramLanguage(language)

    fun view(streaming: Boolean, chosen: DiagramMode?, failed: Boolean): DiagramMode {
        val answer = diagramView(
            streaming = streaming,
            chosen = when (chosen) {
                DiagramMode.Preview -> DiagramView.Preview
                DiagramMode.Source -> DiagramView.Source
                null -> null
            },
            failed = failed,
        )
        return if (answer == DiagramView.Preview) DiagramMode.Preview else DiagramMode.Source
    }

    fun document(source: String, widthPx: Int, palette: Map<String, String>): String =
        diagramDocument(source = source, widthPx = widthPx, palette = palette)

    /** The renderer's file name, so the bundled asset and the page's `script src` cannot disagree. */
    const val ASSET_NAME: String = DIAGRAM_ASSET_NAME
}
