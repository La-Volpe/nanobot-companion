import SwiftUI

/// The activity cluster: one collapsible row per turn holding what nanobot thought, ran
/// and changed. The layout follows the artifact's `.activity` block: a head with a chevron,
/// the label and a status word, and a body of reasoning, tool rows, diffs and source chips.
struct ActivityClusterView: View {
    let cluster: ActivityCluster
    let onToggle: () -> Void
    var onToggleDetails: (String) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onToggle) {
                HStack(spacing: 8) {
                    WebUIIcon(name: "ChevronRight", size: 12)
                        .rotationEffect(.degrees(cluster.isOpen ? 90 : 0))
                        .animation(.easeOut(duration: 0.18), value: cluster.isOpen)
                    Text(cluster.headline)
                        .font(.system(size: 13, weight: .medium))
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(cluster.statusText)
                        .font(.system(size: 11))
                        .foregroundStyle(cluster.hasFailure ? Color.red : Color.secondary)
                }
                .foregroundStyle(.secondary)
                .padding(.horizontal, 11)
                .padding(.vertical, 9)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .frame(minHeight: Design.minimumTarget)
            .accessibilityIdentifier("activity.head")

            if cluster.isOpen {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(cluster.items) { item in
                        ActivityRowView(item: item, onToggleDetails: onToggleDetails)
                    }
                    if !cluster.sourceChips.isEmpty {
                        ActivitySourcesView(chips: cluster.sourceChips)
                    }
                }
                .padding(.horizontal, 11)
                .padding(.bottom, 10)
            }
        }
        .background(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .fill(Palette.secondarySurface)
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("activity.cluster")
    }
}

/// One row in the cluster's body. Each row is one line (glyph, name, a one-line summary, the
/// state), and everything the gateway sent beyond that (arguments, output, the whole reasoning
/// block) is behind the row's own disclosure, closed until the reader opens it. That is the web's
/// shape (`ActivityStep.tsx:77`), which the owner reported this screen was missing.
struct ActivityRowView: View {
    let item: ChatActivityItem
    var onToggleDetails: (String) -> Void = { _ in }

    var body: some View {
        switch item.kind {
        case let .reasoning(text, done):
            reasoning(text: text, done: done, fallback: "Thinking")
        case let .tool(name, summary, status, detail):
            VStack(alignment: .leading, spacing: 2) {
                ActivityLine(
                    icon: Design.Activity.icon(forTool: name),
                    label: name,
                    detail: summary,
                    failed: status == .failed,
                    onTap: { onToggleDetails(item.id) }
                ) {
                    toolState(status, detail: detail)
                }
                .accessibilityIdentifier("activity.tool")
                if item.detailsOpen {
                    details(arguments: summary, output: status == .running ? nil : detail)
                }
            }
        case let .fileEdit(state):
            FileEditView(state: state)
        case let .search(query, sources):
            ActivityLine(
                icon: "Globe",
                label: "web_search",
                detail: query,
                onTap: nil
            ) {
                Text("\(sources.count) sources")
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
            .accessibilityIdentifier("activity.search")
        }
    }

    /// The design's rule: reasoning is quoted by a hairline and is one line of prose; the block
    /// itself is what a tap unfolds.
    @ViewBuilder
    private func reasoning(text: String, done: Bool, fallback: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Button(action: { onToggleDetails(item.id) }) {
                HStack(spacing: 8) {
                    Rectangle().fill(Color.secondary.opacity(0.3)).frame(width: 2)
                    Text(item.preview?.isEmpty == false ? item.preview! : fallback)
                        .font(ReadingType.font(size: Design.Activity.reasoningSize))
                        .italic()
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if !done {
                        Text("Thinking")
                            .font(.system(size: 10.5, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .frame(minHeight: Design.Activity.rowHeight)
            .accessibilityIdentifier("activity.reasoning")
            if item.detailsOpen, !text.isEmpty {
                Text(text)
                    .font(ReadingType.font(size: Design.Activity.reasoningSize))
                    .lineSpacing(ReadingType.lineSpacing)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("activity.reasoning.details")
            }
        }
    }

    /// What the row kept back: the arguments it was called with and the output it returned.
    @ViewBuilder
    private func details(arguments: String?, output: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            detailLine("Arguments", arguments)
            if output != nil {
                detailLine("Output", output)
            }
        }
        .padding(.leading, 30)
        .padding(.bottom, 4)
        .accessibilityIdentifier("activity.tool.details")
    }

    @ViewBuilder
    private func detailLine(_ label: String, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            VStack(alignment: .leading, spacing: 1) {
                Text(label)
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(.secondary)
                // A tool's own output can be a whole file: the row shows the start of it, not all.
                Text(value)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(12)
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private func toolState(_ status: ToolRunStatus, detail: String?) -> some View {
        switch status {
        case .running:
            ProgressView().controlSize(.mini)
        case .done:
            Text("done")
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(.green)
        case .failed:
            // The design's own failed row says the gateway's reason (`exit 4`), on one line.
            Text(detail.map { String($0.split(separator: "\n").first.map(String.init)?.prefix(32) ?? "") } ?? "failed")
                .font(.system(size: 10.5, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(.red)
        }
    }
}

/// One line: the glyph, the name, the summary the core reduced from the arguments, the state.
/// Nothing here wraps, because a wrapping row is the verbosity the owner reported.
private struct ActivityLine<Trailing: View>: View {
    let icon: String
    let label: String
    let detail: String?
    var failed: Bool = false
    let onTap: (() -> Void)?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        let content = HStack(spacing: 8) {
            WebUIIcon(name: icon, size: 13)
                .frame(width: 22, height: 22)
                .background(Color.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                .foregroundStyle(.secondary)
            Text(label)
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(failed ? Color.red : Color.primary)
            if let detail, !detail.isEmpty {
                Text(detail)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 4)
            trailing()
        }
        .frame(minHeight: Design.Activity.rowHeight)

        if let onTap {
            Button(action: onTap) { content.contentShape(Rectangle()) }.buttonStyle(.plain)
        } else {
            content
        }
    }
}

/// One edited file: path, `+n −n`, and the unified diff as rows.
struct FileEditView: View {
    let state: FileEditState

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Text(state.path)
                    .font(.system(size: 11, design: .monospaced))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("+\(state.added)").font(.system(size: 11, weight: .semibold)).foregroundStyle(.green)
                Text("−\(state.deleted)").font(.system(size: 11, weight: .semibold)).foregroundStyle(.red)
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(Palette.code)

            ForEach(Array(state.lines.enumerated()), id: \.offset) { _, line in
                Text(line.text)
                    .font(.system(size: Design.Activity.diffSize, design: .monospaced))
                    .foregroundStyle(line.foreground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 1)
                    .background(line.background)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .strokeBorder(Color.secondary.opacity(0.25), lineWidth: 1)
        )
        .accessibilityIdentifier("activity.diff")
    }
}

private extension DiffLine {
    var background: Color {
        switch kind {
        case .added: Color.green.opacity(0.12)
        case .removed: Color.red.opacity(0.12)
        case .context, .hunk: .clear
        }
    }

    var foreground: Color {
        switch kind {
        case .added: .green
        case .removed: .red
        case .context, .hunk: .secondary
        }
    }
}

struct ActivitySourcesView: View {
    let chips: [SourceChip]

    var body: some View {
        FlowRow(spacing: 6) {
            ForEach(chips) { chip in
                Text(chip.label)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color.primary.opacity(0.06), in: Capsule())
                    .accessibilityIdentifier("activity.source")
            }
        }
    }
}

/// A wrapping row: SwiftUI has no flow layout before iOS 16's `Layout`, and this app's floor
/// is 17, so one small `Layout` is cheaper than a stack of line-broken text.
struct FlowRow: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var total = CGSize.zero
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth + size.width > width, rowWidth > 0 {
                total.width = max(total.width, rowWidth)
                total.height += rowHeight + spacing
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += size.width + (rowWidth > 0 ? spacing : 0)
            rowHeight = max(rowHeight, size.height)
        }
        total.width = max(total.width, rowWidth)
        total.height += rowHeight
        return total
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
