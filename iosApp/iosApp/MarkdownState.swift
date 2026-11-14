import Foundation

/// The markdown tree the chat draws (plan.md D6, C4.7), in Swift's own words. The core parses
/// and this is the adapter's translation of it: rendering decisions stay on this side, and no
/// view sees a framework type.
indirect enum ReadingBlock: Equatable {
    case heading(level: Int, inlines: [ReadingInline])
    case paragraph(inlines: [ReadingInline])
    case list(ordered: Bool, start: Int, items: [ReadingListItem])
    /// `closed` is false while a fence is still arriving, which the caret follows.
    case code(language: String?, code: String, closed: Bool)
    case table(header: [[ReadingInline]], alignments: [ReadingAlignment], rows: [[[ReadingInline]]])
    case quote([ReadingBlock])
    case rule

    /// Everything a copy button or an accessibility label wants: the text with no markup.
    var plainText: String {
        switch self {
        case let .heading(_, inlines), let .paragraph(inlines):
            inlines.readingText
        case let .list(_, _, items):
            items.map(\.inlines.readingText).joined(separator: "\n")
        case let .code(_, code, _):
            code
        case let .table(header, _, rows):
            ([header] + rows).map { $0.map(\.readingText).joined(separator: " | ") }.joined(separator: "\n")
        case let .quote(blocks):
            blocks.map(\.plainText).joined(separator: "\n")
        case .rule:
            ""
        }
    }
}

struct ReadingListItem: Equatable {
    /// How deep the item sits; both platforms indent from this number rather than nesting.
    let depth: Int
    let inlines: [ReadingInline]
}

enum ReadingAlignment: Equatable {
    case none
    case left
    case center
    case right
}

indirect enum ReadingInline: Equatable {
    case text(String)
    case code(String)
    case emphasis([ReadingInline])
    case strong([ReadingInline])
    case link([ReadingInline], destination: String)

    var readingText: String {
        switch self {
        case let .text(value), let .code(value): value
        case let .emphasis(inlines), let .strong(inlines), let .link(inlines, _): inlines.readingText
        }
    }
}

extension Array where Element == ReadingInline {
    var readingText: String { map(\.readingText).joined() }
}
