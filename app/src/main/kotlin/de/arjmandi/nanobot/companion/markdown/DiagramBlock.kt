package de.arjmandi.nanobot.companion.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.core.Diagrams
import de.arjmandi.nanobot.companion.ui.Design
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** What the page reported: the height the diagram measured, or why it could not be drawn. */
internal sealed interface DiagramRender {
    data object Loading : DiagramRender
    data class Ready(val heightDp: Int) : DiagramRender
    data class Failed(val reason: String) : DiagramRender
}

/**
 * A mermaid fence drawn by the same engine the WebUI uses (owner's decision, 2026-11-15): the
 * renderer ships in the app's assets, the page is built by `:core` (`diagramDocument`), and the
 * reader can flip a block between the picture and the fence's source.
 *
 * Which half is on screen is the core's rule (`Diagrams.view`); this draws it. The page reports a
 * height instead of the app guessing one, and a page that fails or never answers leaves the source
 * on screen with a sentence, never an empty box and never a spinner that keeps spinning.
 */
@Composable
internal fun DiagramBlock(
    block: MdBlock.Code,
    blockId: String,
    streaming: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    // Keyed by the fence's text: the next chunk is a new diagram, the reader's tap is not.
    var chosen by rememberSaveable(blockId) { mutableStateOf<String?>(null) }
    var outcome by remember(blockId, block.code) { mutableStateOf<DiagramRender>(DiagramRender.Loading) }
    val failure = outcome as? DiagramRender.Failed
    val mode = Diagrams.view(
        streaming = streaming,
        chosen = chosen?.let { runCatching { DiagramMode.valueOf(it) }.getOrNull() },
        failed = failure != null,
    )
    val ready = outcome as? DiagramRender.Ready

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Design.Radius.field))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("diagram.block"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = block.language ?: stringResource(R.string.markdown_code),
                fontSize = Design.Interface.microSize,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeTab(stringResource(R.string.diagram_preview), mode == DiagramMode.Preview, "diagram.view.preview") {
                    chosen = DiagramMode.Preview.name
                }
                ModeTab(stringResource(R.string.diagram_source), mode == DiagramMode.Source, "diagram.view.source") {
                    chosen = DiagramMode.Source.name
                }
                TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = Design.minimumTarget)) {
                    Text(
                        text = stringResource(if (copied) R.string.markdown_copied else R.string.markdown_copy),
                        fontSize = Design.Interface.microSize,
                    )
                }
            }
        }
        if (mode == DiagramMode.Source) {
            if (failure != null) {
                Text(
                    text = stringResource(R.string.diagram_failed),
                    fontSize = Design.Interface.microSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp).testTag("diagram.failed"),
                )
            }
            SourceLines(code = block.code, testTag = "diagram.source")
        } else {
            // The diagram is laid out to the width the panel has, because the page wraps the
            // drawing and the phone must not scroll sideways to read it.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val width = with(LocalDensity.current) { (maxWidth - 20.dp).toPx().toInt() }
                val palette = diagramPalette()
                val document = remember(block.code, width, palette) {
                    Diagrams.document(source = block.code, widthPx = width, palette = palette)
                }
                Box {
                    // One web view for the block's whole life: it is what measures the diagram,
                    // so it is mounted while it has nothing to show and the source stands in.
                    DiagramWebView(
                        document = document,
                        onOutcome = { outcome = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (ready != null) ready.heightDp.dp else 1.dp)
                            .alpha(if (ready != null) 1f else 0f)
                            .testTag(if (ready != null) "diagram.preview" else "diagram.measuring"),
                    )
                    if (ready == null) SourceLines(code = block.code, testTag = "diagram.source")
                }
            }
        }
    }
}

/**
 * The two halves of the header, as the design draws a segmented choice: the one in force is the
 * one with weight, and both are a thumb's target.
 */
@Composable
private fun ModeTab(label: String, selected: Boolean, tag: String, onTap: () -> Unit) {
    TextButton(onClick = onTap, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag(tag)) {
        Text(
            text = label,
            fontSize = Design.Interface.microSize,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * The app's light/dark tokens as the renderer's variables, so a diagram is drawn in the palette the
 * reader is already looking at. It is the WebUI's `readPalette`, with the Material scheme in its
 * place.
 */
@Composable
private fun diagramPalette(): Map<String, String> {
    val scheme = MaterialTheme.colorScheme
    fun hex(colour: Color): String = String.format("#%06x", 0xFFFFFF and colour.toArgb())
    return mapOf(
        "--pele-bg" to hex(scheme.surfaceVariant),
        "--pele-fg" to hex(scheme.onSurface),
        "--pele-muted" to hex(scheme.onSurfaceVariant),
        "--pele-line" to hex(scheme.onSurface),
        "--pele-surface" to hex(scheme.surface),
        "--pele-surface-alt" to hex(scheme.surfaceVariant),
        "--pele-border" to hex(scheme.outline),
        "--pele-accent" to hex(scheme.primary),
        "--pele-critical" to hex(scheme.error),
    )
}

/**
 * The page itself: a `WebView` over one bundled document. Every route out of it is shut at the
 * WebView, not in the page alone: `blockNetworkLoads` stops network fetches, the settings refuse
 * secondary windows and content urls, and the client refuses to navigate or load anything that is
 * not the document and the renderer beside it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiagramWebView(
    document: String,
    onOutcome: (DiagramRender) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val report by rememberUpdatedState(onOutcome)
    AndroidView(
        modifier = modifier,
        factory = { viewContext -> configured(viewContext) { report(it) } },
        update = { webView ->
            if (webView.tag != document) {
                webView.tag = document
                DiagramPages.load(context, document)?.let { webView.loadUrl(it.toURI().toString()) }
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

private fun configured(context: Context, report: (DiagramRender) -> Unit): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        // The renderer and the page are bundled files; nothing else is readable.
        allowFileAccess = true
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        // A diagram is text from a model: the page it draws itself in has no route out.
        blockNetworkLoads = true
        blockNetworkImage = true
        loadsImagesAutomatically = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        domStorageEnabled = false
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
        loadWithOverviewMode = false
        useWideViewPort = false
    }
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    isFocusable = false
    isLongClickable = false
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    setOnLongClickListener { true }
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            readOutcome(view, attempt = 0) { report(it) }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !DiagramPages.belongs(context, request.url)

        // A subresource that is not ours (a stylesheet, a font, a picture, a beacon) is not
        // fetched: it is answered here with nothing.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (DiagramPages.belongs(context, request.url)) {
                null
            } else {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
    }
}

/**
 * The page publishes its outcome in a variable rather than through a JavaScript interface, so
 * there is no native object in the WebView for a crafted diagram to reach; this reads that
 * variable until it says something, and gives up rather than leaving a spinner behind.
 */
private fun readOutcome(webView: WebView, attempt: Int, report: (DiagramRender) -> Unit) {
    if (attempt > MAX_ATTEMPTS) {
        report(DiagramRender.Failed("deadline"))
        return
    }
    webView.evaluateJavascript(
        "(function(){var s=window.companionDiagram;return s?(s.kind+'|'+s.height+'|'+s.reason):'none';})()",
    ) { value ->
        val text = value.orEmpty().trim('"')
        when {
            text.startsWith("ready|") -> {
                val height = text.split('|').getOrNull(1)?.toDoubleOrNull()?.toInt() ?: 0
                // A page that measured nothing drew nothing: the reader gets the source.
                if (height > 0) report(DiagramRender.Ready(height)) else report(DiagramRender.Failed("policy"))
            }
            text.startsWith("failed|") -> {
                val reason = text.split('|').getOrNull(2).orEmpty()
                report(DiagramRender.Failed(reason.ifEmpty { "render" }))
            }
            else -> webView.postDelayed({ readOutcome(webView, attempt + 1, report) }, POLL_INTERVAL_MS)
        }
    }
}

private const val MAX_ATTEMPTS = 60
private const val POLL_INTERVAL_MS = 50L

/**
 * Where the page and the renderer live on disk. A `file://` page can only read files with
 * `allowFileAccess`, which would reach the whole app's data; keeping the pair in one directory of
 * its own and refusing every other url is what narrows that to the two files this needs.
 */
private object DiagramPages {

    @Volatile
    private var directory: File? = null

    fun belongs(context: Context, url: android.net.Uri): Boolean {
        if (url.scheme != "file") return false
        val root = root(context) ?: return false
        return File(url.path.orEmpty()).parentFile == root
    }

    fun load(context: Context, document: String): File? {
        val root = root(context) ?: return null
        return runCatching {
            File(root, "${UUID.randomUUID()}.html").apply { writeText(document) }
        }.getOrNull()
    }

    private fun root(context: Context): File? {
        directory?.let { return it }
        return synchronized(this) {
            directory ?: run {
                val root = File(context.cacheDir, "diagrams").apply { mkdirs() }
                val asset = File(root, Diagrams.ASSET_NAME)
                if (!asset.exists()) {
                    runCatching {
                        context.assets.open(Diagrams.ASSET_NAME).use { input ->
                            asset.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                if (!asset.exists()) null else root.also { directory = it }
            }
        }
    }
}
