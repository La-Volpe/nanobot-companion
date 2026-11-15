package de.arjmandi.nanobot.companion.facade

/**
 * A diagram fence's view, and the document its WebView draws.
 *
 * The WebUI's `MermaidBlock` is the reference (webui `markdown-diagrams/`): it opens on the source
 * while the answer is still arriving, on the picture once the turn has ended, and it goes back to
 * the source when the renderer refuses the diagram. The rule is three lines, and it is here rather
 * than in each platform, because a client that decided it twice would drift.
 */

/** The two halves of a diagram block: the rendered picture, or the fence as it was written. */
public enum class DiagramView { Preview, Source }

/** The one fence language the WebUI draws as a diagram. */
public const val DIAGRAM_LANGUAGE: String = "mermaid"

/** True for the fence the diagram block draws; `mermaid` is matched as the parser stores it. */
public fun isDiagramLanguage(language: String?): Boolean =
    language?.trim()?.lowercase() == DIAGRAM_LANGUAGE

/**
 * The view a diagram block shows. `chosen` is the reader's tap, which outranks the default until
 * the renderer fails: a failed diagram falls back to its source whatever the reader picked, because
 * a box that cannot draw the diagram is worse than the text it was made from.
 */
public fun diagramView(streaming: Boolean, chosen: DiagramView? = null, failed: Boolean = false): DiagramView =
    when {
        failed -> DiagramView.Source
        chosen != null -> chosen
        streaming -> DiagramView.Source
        else -> DiagramView.Preview
    }

/**
 * The renderer both app bundles ship, byte for byte from the WebUI's own dependency: Pele 1.9.0,
 * MIT, `nanobot/webui/node_modules/pele/dist/pele.global.js` (its licence text sits beside it in
 * each bundle). The document loads this and nothing else, so a diagram costs no network call.
 */
public const val DIAGRAM_ASSET_NAME: String = "pele.global.js"

/**
 * The whole WebView document for one diagram: the fence's source, the app's palette, and the
 * renderer, wired together. Pure, so the escaping that keeps untrusted source out of the HTML and
 * the policy that keeps the page offline are ordinary unit tests.
 *
 * The page reports its outcome in a variable (`companionDiagram`) instead of calling into native
 * code, so neither platform needs a JavaScript bridge: a crafted diagram has nothing to call.
 */
public fun diagramDocument(
    source: String,
    widthPx: Int,
    palette: Map<String, String>,
): String {
    val width = widthPx.coerceIn(1, MAX_WIDTH)
    val payload = buildString {
        append("""{"source":""").append(jsonString(source))
        append(""","width":""").append(width)
        append(""","limit":""").append(SOURCE_LIMIT)
        append(""","palette":{""")
        colours(palette).entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append(jsonString(entry.key)).append(':').append(jsonString(entry.value))
        }
        append("}}")
    }
    return DOCUMENT_HEAD + payload + DOCUMENT_BODY
}

// The palette is the app's own tokens, so a value is a colour and nothing else: a variable name
// that is not one of the renderer's, or a value that is not a colour, is dropped rather than
// escaped, because a dropped colour is a wrong shade and an escaped one is a stylesheet.
private val PALETTE_NAME = Regex(
    """--pele-(?:bg|fg|muted|line|surface|surface-alt|border|accent|critical|series-[1-8])""",
)
private val COLOUR_VALUE = Regex(
    """#(?:[0-9a-fA-F]{3,8})|(?:rgb|rgba|hsl|hsla)\([0-9.,%\s/+~-]+\)""",
)

private fun colours(palette: Map<String, String>): Map<String, String> =
    palette
        .filterKeys { PALETTE_NAME.matches(it) }
        .filterValues { COLOUR_VALUE.matches(it.trim()) }
        .mapValues { it.value.trim() }
        .toList()
        .sortedBy { it.first }
        .toMap()

/** A JSON string literal that cannot end the `script` element it sits in, or start a comment. */
private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { char ->
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            // `<!--`, `-->` and `</script` are the three ways out of an element; none of them need
            // the character itself to mean what it means, so the payload carries none of them.
            char == '<' -> append("\\u003c")
            char == '>' -> append("\\u003e")
            char == '&' -> append("\\u0026")
            char == '\u2028' -> append("\\u2028")
            char == '\u2029' -> append("\\u2029")
            else -> append(char)
        }
    }
    append('"')
}

private const val MAX_WIDTH = 4096
private const val SOURCE_LIMIT = 50_000

private val DOCUMENT_HEAD = """<!doctype html>
<html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' file:; style-src 'unsafe-inline'; img-src 'none'; media-src 'none'; font-src 'none'; connect-src 'none'; frame-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
:root{--pele-font:-apple-system,"SF Pro Text",Roboto,system-ui,sans-serif;--pele-font-mono:ui-monospace,SFMono-Regular,Menlo,monospace;--pele-radius:4px}
html,body{margin:0;padding:0;background:transparent;-webkit-text-size-adjust:100%;text-size-adjust:100%;overflow:hidden}
#diagram-stage{box-sizing:border-box;width:100%}
#diagram-stage svg{display:block;max-width:100%;height:auto;margin:0 auto}
</style></head>
<body><div id="diagram-stage"></div>
<script id="diagram-payload" type="application/json">"""

private val DOCUMENT_BODY = """</script>
<script src="${DIAGRAM_ASSET_NAME}"></script>
<script>
(function () {
  var SANS = "-apple-system,'SF Pro Text',Roboto,system-ui,sans-serif";
  var MONO = "ui-monospace,SFMono-Regular,Menlo,monospace";
  var BANNED = /^(script|foreignObject|iframe|frame|object|embed|audio|video|image|handler)$/i;
  var state = window.companionDiagram = { kind: "loading", height: 0, reason: "" };
  function report(value) {
    state.kind = value.kind;
    state.height = value.height || 0;
    state.reason = value.reason || "";
    try {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.diagram) {
        window.webkit.messageHandlers.diagram.postMessage({ kind: state.kind, height: state.height, reason: state.reason });
      }
    } catch (error) { }
  }
  // The renderer escapes its own text and drops links, but this is a WebView in a chat client: the
  // tree is walked once more before it enters the document, because no escaping bug downstream
  // should be able to become a script, a frame or a request.
  function sanitize(root) {
    var nodes = [root].concat(Array.prototype.slice.call(root.querySelectorAll("*")));
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (BANNED.test(node.nodeName)) {
        if (node.parentNode) node.parentNode.removeChild(node);
        continue;
      }
      var attributes = Array.prototype.slice.call(node.attributes || []);
      for (var j = 0; j < attributes.length; j++) {
        var name = attributes[j].name;
        var lower = name.toLowerCase();
        var value = attributes[j].value;
        if (lower.indexOf("on") === 0 || lower === "src") node.removeAttribute(name);
        else if ((lower === "href" || lower === "xlink:href") && value.charAt(0) !== "#") node.removeAttribute(name);
      }
    }
  }
  try {
    var payload = JSON.parse(document.getElementById("diagram-payload").textContent);
    var stage = document.getElementById("diagram-stage");
    var root = document.documentElement;
    Object.keys(payload.palette).forEach(function (key) { root.style.setProperty(key, payload.palette[key]); });
    if (!payload.source || !payload.source.trim()) { report({ kind: "failed", reason: "empty" }); return; }
    if (payload.source.length > payload.limit) { report({ kind: "failed", reason: "limit" }); return; }
    if (typeof pele === "undefined") { report({ kind: "failed", reason: "asset" }); return; }
    var context = document.createElement("canvas").getContext("2d");
    if (!context) { report({ kind: "failed", reason: "capability" }); return; }
    var result = pele.render(payload.source, {
      // Pele's TextMeasurer contract: the style bits are bold, italic, mono, sub, sup, underline.
      measurer: { width: function (text, size, style) {
        var effective = (style & 24) ? size / 1.2 : size;
        context.font = ((style & 2) ? "italic " : "") + ((style & 1) ? "bold " : "") + effective + "px " + ((style & 4) ? MONO : SANS);
        return context.measureText(text).width;
      } },
      fontFamily: SANS, fontFamilyMono: MONO, fontSize: 14,
      maxWidth: payload.width, responsive: false, links: false, images: false,
      limit: payload.limit, maxEdges: 500, outputLimit: 1048576
    });
    var parsed = new DOMParser().parseFromString(result.svg, "image/svg+xml");
    if (!parsed.documentElement || parsed.documentElement.nodeName.toLowerCase() !== "svg") {
      report({ kind: "failed", reason: "policy" });
      return;
    }
    sanitize(parsed.documentElement);
    stage.appendChild(document.importNode(parsed.documentElement, true));
    report({ kind: "ready", height: Math.ceil(stage.getBoundingClientRect().height) });
  } catch (error) {
    var reason = (error && (error.code || error.name)) || "render";
    report({ kind: "failed", reason: String(reason) });
  }
})();
</script>
</body></html>
"""
