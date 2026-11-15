package de.arjmandi.nanobot.companion.facade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which half of a mermaid block a reader sees (the owner's 2026-11-15 decision: a bundled WebView
 * with a preview/source toggle). The default is the WebUI's — source while the turn is arriving,
 * preview once it has ended — and it is asserted here so neither platform can decide it again.
 */
class DiagramViewTest {

    @Test
    fun aStreamingTurnOpensOnTheSource() {
        assertEquals(DiagramView.Source, diagramView(streaming = true))
    }

    @Test
    fun aSettledTurnOpensOnThePreview() {
        assertEquals(DiagramView.Preview, diagramView(streaming = false))
    }

    @Test
    fun theReadersTapOutranksTheDefaultInBothDirections() {
        assertEquals(
            DiagramView.Preview,
            diagramView(streaming = true, chosen = DiagramView.Preview),
            "a reader can look at the diagram before the turn ends",
        )
        assertEquals(
            DiagramView.Source,
            diagramView(streaming = false, chosen = DiagramView.Source),
            "and at the source after it",
        )
    }

    @Test
    fun aDiagramThatFailedToRenderFallsBackToItsSourceWhateverWasChosen() {
        assertEquals(DiagramView.Source, diagramView(streaming = false, failed = true))
        assertEquals(DiagramView.Source, diagramView(streaming = false, chosen = DiagramView.Preview, failed = true))
    }

    @Test
    fun aRendererThatRecoversGivesTheViewBack() {
        // Retrying after a failure asks the question again with failed = false, and the answer is
        // the default: the failure is not remembered as a choice.
        assertEquals(DiagramView.Preview, diagramView(streaming = false, chosen = null, failed = false))
    }

    @Test
    fun mermaidIsTheOneDiagramLanguage() {
        assertTrue(isDiagramLanguage("mermaid"))
        assertTrue(isDiagramLanguage("MERMAID"), "the fence language is matched case-insensitively")
        assertFalse(isDiagramLanguage("python"))
        assertFalse(isDiagramLanguage("mermaidx"))
        assertFalse(isDiagramLanguage(null))
        assertFalse(isDiagramLanguage(""))
    }
}
