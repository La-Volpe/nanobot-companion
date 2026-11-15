package de.arjmandi.nanobot.companion.facade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The document one diagram's WebView loads. It is built here, in Kotlin, for three reasons that are
 * each a test below: the source is untrusted text that has to survive the trip into HTML without
 * closing a tag, the page must reach nothing but the bundled renderer, and the palette is the app's
 * own light/dark tokens. A platform that built this string itself would have to prove all three
 * again, which is how a WebView ends up with a network route nobody noticed.
 */
class DiagramDocumentTest {

    private val palette = mapOf(
        "--pele-bg" to "#f5f5f5",
        "--pele-fg" to "#1e1e20",
        "--pele-accent" to "rgb(39, 39, 42)",
    )

    private fun payload(document: String): JsonObject {
        val opener = """<script id="diagram-payload" type="application/json">"""
        val start = document.indexOf(opener)
        assertTrue(start >= 0, "the document carries a payload script with a stable id")
        val from = start + opener.length
        val end = document.indexOf("</script>", from)
        assertTrue(end > from, "the payload script is closed")
        return Json.parseToJsonElement(document.substring(from, end)).jsonObject
    }

    @Test
    fun theSourceArrivesExactlyAsTheModelWroteIt() {
        val source = "flowchart TD\n  A[\"Ask\"] --> B{\"Tool?\"}\n  B -->|no| C[\"Answer\"]"
        val document = diagramDocument(source, widthPx = 336, palette = palette)
        assertEquals(source, payload(document)["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFenceCannotCloseTheScriptItIsEmbeddedIn() {
        // The hostile fence: a label that ends the script element, an img with an error handler and
        // a line comment marker. It is still just a diagram's source, so it has to arrive intact.
        val source = "flowchart TD\n  A[\"</script><img src=x onerror=alert(1)>\"] --> B[ok]\n" +
            "  %% ]]> --> <!-- </script>"
        val document = diagramDocument(source, widthPx = 336, palette = palette)
        val opener = """<script id="diagram-payload" type="application/json">"""
        val from = document.indexOf(opener) + opener.length
        val body = document.substring(from, document.indexOf("</script>", from))
        assertFalse(body.contains('<'), "the payload never contains a character that can open a tag")
        assertFalse(body.contains("-->"), "an HTML comment cannot be opened or continued from the payload")
        assertEquals(source, payload(document)["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun thePageReachesNothingButTheBundledRenderer() {
        val document = diagramDocument("flowchart TD\n A-->B", widthPx = 336, palette = palette)
        assertFalse(document.contains("http://"), "no absolute url, not even in a comment")
        assertFalse(document.contains("https://"), "no absolute url, not even in a comment")
        assertFalse(document.contains("//cdn"), "no protocol-relative url")
        assertTrue(document.contains("""src="pele.global.js""""), "the one script is the bundled asset")
        assertEquals(
            1,
            Regex("""\ssrc=""").findAll(document).count(),
            "one subresource, and it is the asset",
        )
        val policy = Regex("""Content-Security-Policy" content="([^"]+)""").find(document)
        assertNotNull(policy, "the page carries a policy of its own")
        val rules = policy.groupValues[1]
        assertTrue(rules.contains("default-src 'none'"), "nothing is allowed unless named")
        assertTrue(rules.contains("connect-src 'none'"), "the page cannot open a socket")
        assertTrue(rules.contains("img-src 'none'"), "the page cannot load a picture")
        assertTrue(rules.contains("base-uri 'none'"), "a relative url cannot be re-pointed")
        assertTrue(rules.contains("form-action 'none'"), "the page cannot post anywhere")
    }

    @Test
    fun aPaletteValueCannotLeaveItsDeclaration() {
        val hostile = mapOf(
            "--pele-fg" to "#fff;}</style><script>alert(1)</script>",
            "--pele-bg" to "#ffffff",
            "colour" to "#000000",
            "--pele-not-a-variable" to "#000000",
        )
        val document = diagramDocument("flowchart TD\n A-->B", widthPx = 336, palette = hostile)
        assertFalse(document.contains("<script>alert"), "a value is not a stylesheet")
        val colours = payload(document)["palette"]!!.jsonObject
        assertEquals(setOf("--pele-bg"), colours.keys, "only known variables with colour values survive")
        assertEquals("#ffffff", colours["--pele-bg"]!!.jsonPrimitive.content)
    }

    @Test
    fun theWidthIsTheOneTheDiagramLaysOutIn() {
        assertEquals(336, payload(diagramDocument("flowchart TD\n A-->B", 336, palette))["width"]!!.jsonPrimitive.int)
        assertEquals(
            1,
            payload(diagramDocument("flowchart TD\n A-->B", 0, palette))["width"]!!.jsonPrimitive.int,
            "a zero width would make the renderer divide the layout by nothing",
        )
        assertEquals(
            4096,
            payload(diagramDocument("flowchart TD\n A-->B", 99_999, palette))["width"]!!.jsonPrimitive.int,
            "a wild width is clamped rather than handed to the renderer",
        )
    }

    @Test
    fun theDocumentReportsItsOwnOutcomeWithoutABridge() {
        // The status is a variable the platform reads; the page never calls into the app, so there
        // is no native object for a crafted diagram to reach.
        val document = diagramDocument("flowchart TD\n A-->B", widthPx = 336, palette = palette)
        assertTrue(document.contains("companionDiagram"), "the outcome is published as a variable")
        assertTrue(document.contains("diagram-stage"), "the diagram is drawn into the measured element")
    }
}
