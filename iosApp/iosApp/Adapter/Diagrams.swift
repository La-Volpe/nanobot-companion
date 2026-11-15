import Foundation
import core

/// The diagram rules, which are the core's (`MarkdownDiagrams.kt`), in Swift's own names.
///
/// This is the adapter's whole job here: `LayeringTests` keeps `import core` inside `Adapter/`, so a
/// view that could ask the framework directly would break the one arrow the project draws. Which
/// half of a diagram a block shows — source while the turn arrives, the picture once it has ended,
/// source when the renderer refuses it — is therefore asked for here and answered by `:core`.
enum Diagrams {

    static func isDiagram(language: String?) -> Bool {
        MarkdownDiagramsKt.isDiagramLanguage(language: language)
    }

    static func view(streaming: Bool, chosen: ReadingDiagramView?, failed: Bool) -> ReadingDiagramView {
        let answer = MarkdownDiagramsKt.diagramView(
            streaming: streaming,
            chosen: chosen.map { $0 == .preview ? DiagramView.preview : DiagramView.source },
            failed: failed
        )
        return answer == DiagramView.preview ? .preview : .source
    }

    static func document(source: String, width: Int, palette: [String: String]) -> String {
        MarkdownDiagramsKt.diagramDocument(source: source, widthPx: Int32(width), palette: palette)
    }

    /// The renderer's file name, so the bundle lookup and the page's `script src` cannot disagree.
    static var assetName: String { MarkdownDiagramsKt.DIAGRAM_ASSET_NAME }
}
