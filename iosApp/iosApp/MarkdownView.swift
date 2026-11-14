import SwiftUI

/// A reply, drawn from the core's markdown tree (I5): prose at the reading scale, bullets,
/// tables that scroll inside their own box, and code blocks with a language chip, line numbers
/// on multiline code and a Copy that confirms for 1.5 s.
///
/// The view has no branches worth testing: the confirmation state, which block is copied and
/// for how long are the composer's (`ComposerViewModel.copy`).
struct ReadingBlocksView: View {
    /// Settings → Appearance's reading size; the design's S/M/L, not a zoom.
    @Environment(\.readingSize) private var readingSize
    let blocks: [ReadingBlock]
    /// The turn's id, so a block's identity survives a re-render without hashing its text.
    let idPrefix: String
    let isCopied: (String) -> Bool
    let onCopy: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { index, block in
                blockView(block, id: "\(idPrefix)-\(index)")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func blockView(_ block: ReadingBlock, id: String) -> some View {
        switch block {
        case let .heading(level, inlines):
            Text(ReadingText.attributed(inlines))
                .font(ReadingType.font(size: readingSize + (level <= 1 ? 3 : 1), weight: .bold))
                .lineSpacing(ReadingType.lineSpacing)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("chat.answer")
        case let .paragraph(inlines):
            Text(ReadingText.attributed(inlines))
                .font(ReadingType.font(size: readingSize))
                .lineSpacing(readingSize * 0.5)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("chat.answer")
        case let .list(ordered, start, items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(ordered ? "\(start + index)." : "•")
                            .font(ReadingType.font(size: readingSize))
                            .foregroundStyle(.secondary)
                            .frame(minWidth: 16, alignment: .trailing)
                        Text(ReadingText.attributed(item.inlines))
                            .font(ReadingType.font(size: readingSize))
                            .lineSpacing(readingSize * 0.5)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("chat.answer")
                    }
                    .padding(.leading, CGFloat(item.depth) * 16)
                }
            }
        case let .code(language, code, _):
            ReadingCodeBlock(
                id: id,
                language: language,
                code: code,
                copied: isCopied(id),
                onCopy: { onCopy(id) }
            )
        case let .table(header, alignments, rows):
            ReadingTableView(header: header, alignments: alignments, rows: rows)
        case let .quote(blocks):
            ReadingBlocksView(blocks: blocks, idPrefix: "\(id)-quote", isCopied: isCopied, onCopy: onCopy)
                .padding(.leading, 10)
                .overlay(alignment: .leading) {
                    Rectangle().fill(Color.secondary.opacity(0.35)).frame(width: 2)
                }
        case .rule:
            Divider()
        }
    }
}

/// A fence: the language in the head, Copy on the right, and line numbers once there is more
/// than one line of code.
struct ReadingCodeBlock: View {
    @Environment(\.readingSize) private var readingSize
    let id: String
    let language: String?
    let code: String
    let copied: Bool
    let onCopy: () -> Void

    private var lines: [String] { code.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) }
    private var numbered: Bool { lines.count > 1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text((language ?? "code").uppercased())
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(0.4)
                Spacer()
                Button(action: onCopy) {
                    Text(copied ? "Copied" : "Copy")
                        .font(.system(size: 11))
                        .frame(minWidth: Design.minimumTarget, minHeight: Design.minimumTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(copied ? "reading.copied" : "reading.copy")
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 2)
            .background(Palette.code)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 8) {
                    if numbered {
                        VStack(alignment: .trailing, spacing: 0) {
                            ForEach(Array(lines.indices), id: \.self) { index in
                                Text("\(index + 1)")
                                    .font(.system(size: readingSize * 0.72, design: .monospaced))
                                    .foregroundStyle(.secondary.opacity(0.7))
                            }
                        }
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                            Text(line.isEmpty ? " " : line)
                                .font(.system(size: readingSize * 0.72, design: .monospaced))
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
        }
        .background(Palette.secondarySurface)
        .clipShape(RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .strokeBorder(Color.secondary.opacity(0.25), lineWidth: 1)
        )
    }
}

/// A table scrolls inside its own box, which is the only way a five-column limit table stays
/// readable on a phone (artifact: `.table-wrap`).
struct ReadingTableView: View {
    let header: [[ReadingInline]]
    let alignments: [ReadingAlignment]
    let rows: [[[ReadingInline]]]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                row(header, isHeader: true)
                ForEach(Array(rows.enumerated()), id: \.offset) { _, cells in
                    row(cells, isHeader: false)
                    Divider()
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .strokeBorder(Color.secondary.opacity(0.3), lineWidth: 1)
        )

    }

    private func row(_ cells: [[ReadingInline]], isHeader: Bool) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { index, cell in
                Text(ReadingText.attributed(cell))
                    .font(.system(size: Design.Interface.captionSize, weight: isHeader ? .semibold : .regular))
                    .foregroundStyle(isHeader ? Color.secondary : Color.primary)
                    .multilineTextAlignment(textAlignment(index))
                    .frame(minWidth: 90, alignment: alignment(index))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
            }
        }
        .background(isHeader ? Palette.secondarySurface : Color.clear)
    }

    private func alignment(_ index: Int) -> Alignment {
        switch alignments.indices.contains(index) ? alignments[index] : .none {
        case .right: .trailing
        case .center: .center
        case .left, .none: .leading
        }
    }

    private func textAlignment(_ index: Int) -> TextAlignment {
        switch alignments.indices.contains(index) ? alignments[index] : .none {
        case .right: .trailing
        case .center: .center
        case .left, .none: .leading
        }
    }
}

/// Inline marks become one attributed string rather than nested `Text`s, so a bold word keeps
/// its line's spacing and selection.
enum ReadingText {
    static func attributed(_ inlines: [ReadingInline]) -> AttributedString {
        inlines.reduce(into: AttributedString()) { result, inline in
            result.append(attributed(inline))
        }
    }

    private static func attributed(_ inline: ReadingInline) -> AttributedString {
        switch inline {
        case let .text(value):
            return AttributedString(value)
        case let .code(value):
            var run = AttributedString(value)
            run.inlinePresentationIntent = .code
            return run
        case let .emphasis(inlines):
            var run = attributed(inlines)
            run.inlinePresentationIntent = .emphasized
            return run
        case let .strong(inlines):
            var run = attributed(inlines)
            run.inlinePresentationIntent = .stronglyEmphasized
            return run
        case let .link(inlines, destination):
            var run = attributed(inlines)
            if let url = URL(string: destination) { run.link = url }
            return run
        }
    }
}
