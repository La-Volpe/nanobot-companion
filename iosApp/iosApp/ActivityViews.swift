import SwiftUI

/// The activity cluster (I4): one collapsible row per turn holding what nanobot thought, ran
/// and changed. The layout follows the artifact's `.activity` block — a head with a chevron,
/// the label and a status word, and a body of reasoning, tool rows, diffs and source chips.
struct ActivityClusterView: View {
    let cluster: ActivityCluster
    let onToggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onToggle) {
                HStack(spacing: 8) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
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
                        ActivityRowView(item: item)
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
                .fill(Color(.secondarySystemBackground))
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("activity.cluster")
    }
}

struct ActivityRowView: View {
    let item: ChatActivityItem

    var body: some View {
        switch item.kind {
        case let .reasoning(text, _):
            Text(text)
                .font(ReadingType.font(size: Design.Activity.reasoningSize))
                .lineSpacing(ReadingType.lineSpacing)
                .foregroundStyle(.secondary)
                .padding(.leading, 10)
                .overlay(alignment: .leading) {
                    Rectangle().fill(Color.secondary.opacity(0.3)).frame(width: 2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("activity.reasoning")
        case let .tool(name, summary, status, detail):
            HStack(spacing: 8) {
                Image(systemName: Design.Activity.icon(forTool: name))
                    .font(.system(size: 12))
                    .frame(width: 22, height: 22)
                    .background(Color.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .foregroundStyle(.secondary)
                Text(name).font(.system(size: 13, weight: .semibold))
                Text(summary ?? "")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 4)
                toolState(status, detail: detail)
            }
            .frame(minHeight: Design.Activity.rowHeight)
            .accessibilityIdentifier("activity.tool")
        case let .fileEdit(state):
            FileEditView(state: state)
        case let .search(query, sources):
            HStack(spacing: 8) {
                Image(systemName: "globe")
                    .font(.system(size: 12))
                    .frame(width: 22, height: 22)
                    .background(Color.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .foregroundStyle(.secondary)
                Text("web_search").font(.system(size: 13, weight: .semibold))
                Text(query)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text("\(sources.count) sources")
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
            .frame(minHeight: Design.Activity.rowHeight)
            .accessibilityIdentifier("activity.search")
        }
    }

    @ViewBuilder
    private func toolState(_ status: ToolRunStatus, detail: String?) -> some View {
        switch status {
        case .running:
            ProgressView().controlSize(.mini)
        case .done:
            Text(detail ?? "done")
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(.green)
        case .failed:
            Text(detail ?? "failed")
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(.red)
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
            .background(Color(.tertiarySystemBackground))

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
