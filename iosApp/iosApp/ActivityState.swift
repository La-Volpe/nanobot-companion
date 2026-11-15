import Foundation

/// One row inside a turn's activity cluster (I4), as Swift's own values. The core reduced the
/// frames into `ActivityItem`; the adapter maps each case here and nothing else branches on it.
struct ChatActivityItem: Identifiable, Equatable {
    enum Kind: Equatable {
        case reasoning(text: String, done: Bool)
        case tool(name: String, summary: String?, status: ToolRunStatus, detail: String?)
        case fileEdit(FileEditState)
        case search(query: String, sources: [String])
    }

    let id: String
    let kind: Kind
    /// True once the reader opened the row's disclosure; arguments and output stay behind it.
    var detailsOpen: Bool = false

    /// The line a reasoning row shows: the web compacts the block to one line of prose
    /// (`reasoning-preview.ts:1`) and keeps the rest for the reader who asks for it.
    var preview: String? {
        if case let .reasoning(text, _) = kind { return ChatActivityItem.compactReasoningPreview(text) }
        return nil
    }

    func openingDetails() -> ChatActivityItem {
        var copy = self
        copy.detailsOpen = true
        return copy
    }

    /// `compactReasoningPreview` (webui `activity/reasoning-preview.ts`): links to their text,
    /// markdown away, every run of whitespace to one space. Empty stays empty; the row says its
    /// own word for that. The Android twin is `compactReasoningPreview` in `ActivityModel.kt`.
    static func compactReasoningPreview(_ value: String) -> String {
        var text = value.replacingOccurrences(
            of: "\\[([^\\]]+)]\\([^)]+\\)",
            with: "$1",
            options: .regularExpression
        )
        text = text.replacingOccurrences(of: "[*_#`~]+", with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

enum ToolRunStatus: Equatable {
    case running
    case done
    case failed
}

/// One edited file, with its unified diff split into rows the view can draw.
struct FileEditState: Equatable {
    let path: String
    let status: ToolRunStatus
    let added: Int
    let deleted: Int
    let operation: String?
    let diff: String

    /// A diff's first two lines are the file headers, and `@@` is a position marker rather
    /// than a change: neither is a row the design draws.
    var lines: [DiffLine] {
        diff.split(separator: "\n", omittingEmptySubsequences: false).compactMap { raw in
            let line = String(raw)
            if line.hasPrefix("--- ") || line.hasPrefix("+++ ") || line.isEmpty { return nil }
            if line.hasPrefix("@@") { return DiffLine(kind: .hunk, text: line) }
            if line.hasPrefix("+") { return DiffLine(kind: .added, text: line) }
            if line.hasPrefix("-") { return DiffLine(kind: .removed, text: line) }
            return DiffLine(kind: .context, text: line)
        }
    }
}

struct DiffLine: Equatable {
    enum Kind: Equatable { case context, added, removed, hunk }

    let kind: Kind
    /// The line as the diff wrote it, marker included, which is what a monospaced row shows.
    let text: String

    /// What follows the marker: the code itself, for a line that is not a hunk marker.
    var body: String {
        switch kind {
        case .hunk: text
        case .context: text
        case .added, .removed: String(text.dropFirst())
        }
    }
}

/// A web-search hit, as the chip says it: the host and the tail of the path.
struct SourceChip: Identifiable, Equatable {
    let id: String
    let label: String

    init(url: String) {
        self.id = url
        guard let parsed = URL(string: url), let host = parsed.host else {
            self.label = url
            return
        }
        let parts = parsed.path.split(separator: "/").map(String.init)
        let tail = parts.suffix(2).joined(separator: "/")
        self.label = tail.isEmpty ? host : "\(host) · \(tail)"
    }
}
