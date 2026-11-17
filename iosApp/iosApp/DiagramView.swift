import SwiftUI
import WebKit

/// A mermaid fence drawn by the same engine the WebUI uses (owner's decision, 2026-11-15): the
/// renderer ships in the bundle, the page is built by `:core` (`diagramDocument`), and the reader
/// can flip a block between the picture and the fence's source.
///
/// Which half a block opens on is `:core`'s too (`diagramView`): source while the turn is arriving,
/// the picture once it has ended, source again when the renderer refuses the diagram. This file
/// draws that decision and remembers the reader's tap; it does not decide it.
struct ReadingDiagramBlock: View {
    let source: String
    let streaming: Bool
    let copied: Bool
    let onCopy: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var chosen: ReadingDiagramView?
    @State private var height: CGFloat = 0
    @State private var failure: String?
    @State private var width: CGFloat = 0

    private var ready: Bool { failure == nil && height > 0 }
    private var view: ReadingDiagramView {
        Diagrams.view(streaming: streaming, chosen: chosen, failed: failure != nil)
    }
    private var spec: DiagramSpec {
        DiagramSpec(
            source: source,
            // The page lays the diagram out in CSS pixels, which are points here; the panel's own
            // padding is what the diagram cannot use.
            width: Int(max(160, width - 24)),
            palette: DiagramPalette.tokens(for: colorScheme)
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            content
        }
        .background(Palette.secondarySurface)
        .clipShape(RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .strokeBorder(Color.secondary.opacity(0.25), lineWidth: 1)
        )
        .background(
            GeometryReader { proxy in
                Color.clear.preference(key: DiagramWidthKey.self, value: proxy.size.width)
            }
        )
        .onPreferenceChange(DiagramWidthKey.self) { width = $0 }
        .onChange(of: source) { _, _ in
            // New text is a new diagram: back to the state the core decides, not the old picture.
            height = 0
            failure = nil
        }
    }

    private var header: some View {
        HStack(spacing: 0) {
            Text("MERMAID")
                .font(.system(size: 10.5, weight: .semibold))
                .tracking(0.4)
                .foregroundStyle(.secondary)
                .padding(.leading, 10)
            Spacer(minLength: 4)
            toggle("Preview", on: view == .preview, id: "diagram.view.preview") { chosen = .preview }
            toggle("Source", on: view == .source, id: "diagram.view.source") { chosen = .source }
            Button(action: onCopy) {
                Text(copied ? "Copied" : "Copy")
                    .font(.system(size: 11))
                    .frame(minWidth: Design.minimumTarget, minHeight: Design.minimumTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .accessibilityIdentifier(copied ? "reading.copied" : "reading.copy")
            .padding(.trailing, 2)
        }
        .background(Palette.secondarySurface)
    }

    private func toggle(_ label: String, on: Bool, id: String, tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            Text(label)
                .font(.system(size: 11, weight: on ? .semibold : .regular))
                .foregroundStyle(on ? Color.primary : Color.secondary)
                .padding(.horizontal, 8)
                .frame(minHeight: Design.minimumTarget)
                .contentShape(Rectangle())
            }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }

    @ViewBuilder
    private var content: some View {
        if view == .source {
            if failure != nil {
                Text(DiagramCopy.failed)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
                    .accessibilityIdentifier("diagram.failed")
            }
            ReadingSourceLines(code: source)
                .accessibilityIdentifier("diagram.source")
        } else {
            // The web view is mounted in preview because it is what measures the diagram; until it
            // answers, the source stands in for it: the same bargain the WebUI makes, and the
            // reason neither platform needs a spinner.
            ZStack(alignment: .top) {
                if width > 0 {
                    DiagramWebView(spec: spec) { outcome in
                        switch outcome {
                        case let .ready(measured): height = measured
                        case let .failed(reason): failure = reason
                        case .loading: break
                        }
                    }
                    .frame(height: ready ? height : 1)
                    .opacity(ready ? 1 : 0)
                    // The picture is one element: an SVG has no text to read out, and the source
                    // beside it is the description a screen reader can actually use.
                    .accessibilityElement()
                    .accessibilityLabel("Diagram")
                    .accessibilityIdentifier("diagram.preview")
                }
                if !ready {
                    ReadingSourceLines(code: source)
                        .accessibilityIdentifier("diagram.source")
                }
            }
        }
    }
}

private struct DiagramWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// What the page reports: the height the diagram measured, or why it could not be drawn.
enum DiagramOutcome: Equatable {
    case loading
    case ready(height: CGFloat)
    case failed(reason: String)
}

struct DiagramSpec: Equatable {
    let source: String
    let width: Int
    let palette: [String: String]
}

enum DiagramCopy {
    /// One sentence, because the fence underneath it is the whole explanation.
    static let failed = "This diagram could not be drawn, so its source is shown."
}

/// The app's light/dark tokens as the renderer's variables, so a diagram is drawn in the palette the
/// reader is already looking at: the WebUI's `readPalette`, with `Palette` in its place.
enum DiagramPalette {
    static func tokens(for scheme: ColorScheme) -> [String: String] {
        let traits = UITraitCollection(userInterfaceStyle: scheme == .dark ? .dark : .light)
        func hex(_ colour: Color) -> String {
            let resolved = UIColor(colour).resolvedColor(with: traits)
            var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
            resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
            return String(
                format: "#%02x%02x%02x",
                Int((red * 255).rounded()), Int((green * 255).rounded()), Int((blue * 255).rounded())
            )
        }
        return [
            "--pele-bg": hex(Palette.secondarySurface),
            "--pele-fg": hex(Palette.foreground),
            "--pele-muted": hex(Palette.mutedForeground),
            "--pele-line": hex(Palette.foreground),
            "--pele-surface": hex(Palette.surface),
            "--pele-surface-alt": hex(Palette.secondarySurface),
            "--pele-border": hex(Palette.border),
            "--pele-accent": hex(Palette.primary),
            "--pele-critical": hex(Palette.destructive),
        ]
    }
}

/// Where the page and the renderer live on disk. One directory holds both, and it is the directory
/// `loadFileURL(_:allowingReadAccessTo:)` grants: the page can read the renderer beside it and
/// nothing else: no app resource, no other document, no origin on a network.
enum DiagramFiles {
    static let directory: URL? = {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent("nanobot-diagrams", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        let name = Diagrams.assetName
        let bundled = Bundle.main.url(
            forResource: (name as NSString).deletingPathExtension,
            withExtension: (name as NSString).pathExtension
        )
        guard let bundled else { return base }
        let destination = base.appendingPathComponent(name)
        if !FileManager.default.fileExists(atPath: destination.path) {
            try? FileManager.default.copyItem(at: bundled, to: destination)
        }
        return base
    }()

    static func page(_ document: String) -> URL? {
        guard let directory else { return nil }
        let url = directory.appendingPathComponent("\(UUID().uuidString).html")
        do {
            try document.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }
}

/// The page itself: a `WKWebView` over one bundled document, with no bridge into the app, no
/// persistent store and every navigation but the document itself cancelled.
struct DiagramWebView: UIViewRepresentable {
    let spec: DiagramSpec
    let onOutcome: (DiagramOutcome) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onOutcome: onOutcome) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // Nothing this page shows is worth keeping, and a persistent store is state a diagram
        // could write into.
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.isFraudulentWebsiteWarningEnabled = false
        // The page reports its outcome through this one handler, whose only effect is a height.
        configuration.userContentController.add(WeakHandler(context.coordinator), name: "diagram")

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        // The app sets the height the diagram asked for; a second scroll inside a chat is a trap.
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsLinkPreview = false
        webView.isInspectable = false
        context.coordinator.load(spec, in: webView)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.onOutcome = onOutcome
        context.coordinator.load(spec, in: webView)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.forget()
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "diagram")
        webView.stopLoading()
    }

    /// The configuration retains its handlers, so the handler cannot be the coordinator itself.
    private final class WeakHandler: NSObject, WKScriptMessageHandler {
        weak var target: Coordinator?
        init(_ target: Coordinator?) { self.target = target }
        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            target?.receive(message)
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var onOutcome: (DiagramOutcome) -> Void
        private var loaded: DiagramSpec?
        private var page: URL?

        init(onOutcome: @escaping (DiagramOutcome) -> Void) { self.onOutcome = onOutcome }

        /// A spec that has not changed is not reloaded: SwiftUI calls this on every scroll tick.
        func load(_ spec: DiagramSpec, in webView: WKWebView) {
            guard loaded != spec else { return }
            loaded = spec
            onOutcome(.loading)
            guard let directory = DiagramFiles.directory else {
                onOutcome(.failed(reason: "asset"))
                return
            }
            let document = Diagrams.document(
                source: spec.source,
                width: spec.width,
                palette: spec.palette
            )
            guard let page = DiagramFiles.page(document) else {
                onOutcome(.failed(reason: "policy"))
                return
            }
            self.page = page
            webView.loadFileURL(page, allowingReadAccessTo: directory)
        }

        func receive(_ message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let kind = body["kind"] as? String else { return }
            switch kind {
            case "ready":
                let measured = CGFloat((body["height"] as? NSNumber)?.doubleValue ?? 0)
                // A page that reports no height has not drawn anything: the reader gets the source
                // rather than a box with nothing in it.
                onOutcome(measured >= 1 ? .ready(height: measured) : .failed(reason: "policy"))
            case "failed":
                onOutcome(.failed(reason: body["reason"] as? String ?? "render"))
            default:
                break
            }
        }

        func forget() { loaded = nil }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            // One document, on disk, in one directory: a link in a diagram is not a way out.
            let allowed = navigationAction.targetFrame?.isMainFrame == true
                && navigationAction.request.url?.isFileURL == true
                && navigationAction.request.url?.path == page?.path
            decisionHandler(allowed ? .allow : .cancel)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationResponse: WKNavigationResponse,
            decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
        ) {
            decisionHandler(navigationResponse.response.url?.isFileURL == true ? .allow : .cancel)
        }

        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            nil
        }
    }
}
