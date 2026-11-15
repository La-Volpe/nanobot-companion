import SwiftUI
import UIKit

/// The thread (plan.md I3): history, streaming answer, one composer row. The view draws
/// `ChatViewModel.rows` and calls one of two commands; every branch is in the model.
struct ChatView: View {
    @ObservedObject var model: ChatViewModel
    @ObservedObject var activity: ActivityViewModel
    @ObservedObject var composer: ComposerViewModel
    @ObservedObject var attachments: AttachmentViewModel
    @ObservedObject var voice: VoiceViewModel
    @ObservedObject var states: StatesViewModel
    /// I6 wires the picker here; until it exists the button says it is not built yet.
    var onAttach: () -> Void = {}

    /// The scroll view's own height and the tail marker's position in the same space: the
    /// difference is the reader's distance from the end, which is what `ThreadViewport` decides on
    /// (webui `ThreadViewport.tsx:1002`).
    @State private var viewportHeight: CGFloat = 0
    @State private var tailBottom: CGFloat = 0

    var body: some View {
        VStack(spacing: 0) {
            if let banner = states.banner {
                ConnectionBannerView(banner: banner) { states.retryConnection() }
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(model.rows) { row in
                            ChatRowView(
                                row: row,
                                cluster: cluster(for: row),
                                copiedBlockId: composer.copiedBlockId,
                                onToggleActivity: toggleActivity(for: row),
                                onToggleDetails: activity.toggleDetails(itemId:),
                                onCopy: copy
                            )
                            .equatable()
                        }
                        ForEach(states.rows) { row in
                            StateRowView(row: row) { action in states.perform(action, in: row) }
                        }
                        if let delivery = states.delivery {
                            DeliveryRowView(delivery: delivery) {
                                Task { await states.resend() }
                            }
                        }
                        Color.clear
                            .frame(height: 1)
                            .id("bottom")
                            .background(
                                GeometryReader { geometry in
                                    Color.clear.preference(
                                        key: ThreadTailKey.self,
                                        value: geometry.frame(in: .named("thread")).maxY
                                    )
                                }
                            )
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .coordinateSpace(name: "thread")
                .background(
                    GeometryReader { geometry in
                        Color.clear.preference(key: ThreadViewportHeightKey.self, value: geometry.size.height)
                    }
                )
                .onPreferenceChange(ThreadViewportHeightKey.self) { viewportHeight = $0 }
                .onPreferenceChange(ThreadTailKey.self) { bottom in
                    tailBottom = bottom
                    model.scrolled(distanceFromBottom: max(0, viewportHeight - bottom))
                }
                .onChange(of: model.rows) { _, _ in
                    // The tail follows the stream only while the reader is at it; an animation per
                    // delta is the layout thrash the owner reported, so this jump is instant
                    // (the web's `jumpTo`, not its `navigateLatestTo`).
                    guard model.viewport.followsNewContent else { return }
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
                .onChange(of: model.rows.first?.id) { _, _ in
                    // A chat opens at its own end, which is where following starts (the web's
                    // pending-conversation scroll).
                    guard !model.rows.isEmpty else { return }
                    model.jumpToLatest()
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }
            composerZone
        }
        .background(Palette.canvas)
    }

    private func cluster(for row: ChatRow) -> ActivityCluster? {
        guard case let .activity(turnId) = row.kind else { return nil }
        return activity.cluster(for: turnId)
    }

    private func toggleActivity(for row: ChatRow) -> () -> Void {
        guard case let .activity(turnId) = row.kind else { return {} }
        return { activity.toggle(turnId: turnId) }
    }

    private func copy(_ blocks: [ReadingBlock], blockId: String) {
        UIPasteboard.general.string = blocks.map(\.plainText).joined(separator: "\n")
        composer.copy(blockId: blockId)
    }

    private var composerZone: some View {
        VStack(spacing: 0) {
            Divider()
            SubagentDockView(rows: states.dock) { states.cancelSubagent(id: $0) }
            if let goal = states.goalStrip {
                GoalStripView(text: goal)
            }
            ComposerBar(model: composer, attachments: attachments, voice: voice, onAttach: onAttach)
        }
    }
}

/// One row of the thread, as its own value: a streaming answer changes on every token, and the
/// rows above it must not be rebuilt on each one, so equality is what decides whether a row draws.
private struct ChatRowView: View, Equatable {
    let row: ChatRow
    let cluster: ActivityCluster?
    let copiedBlockId: String?
    let onToggleActivity: () -> Void
    let onToggleDetails: (String) -> Void
    let onCopy: ([ReadingBlock], String) -> Void

    static func == (lhs: ChatRowView, rhs: ChatRowView) -> Bool {
        lhs.row == rhs.row && lhs.cluster == rhs.cluster && lhs.copiedBlockId == rhs.copiedBlockId
    }

    var body: some View {
        switch row.kind {
        case let .user(text, pending):
            HStack {
                Spacer(minLength: 40)
                VStack(alignment: .trailing, spacing: 4) {
                    Text(text)
                        .font(ReadingType.font(size: 17))
                        .lineSpacing(ReadingType.lineSpacing)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 9)
                        .background(
                            RoundedRectangle(cornerRadius: Design.Radius.bubble, style: .continuous)
                                .fill(Palette.bubble)
                        )
                        .foregroundStyle(.primary)
                    if pending {
                        Text("Sending…")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("chat.pending")
                    }
                }
            }
        case .activity:
            if let cluster {
                ActivityClusterView(
                    cluster: cluster,
                    onToggle: onToggleActivity,
                    onToggleDetails: onToggleDetails
                )
            }
        case let .answer(blocks, streaming):
            VStack(alignment: .leading, spacing: 6) {
                ReadingBlocksView(
                    blocks: blocks,
                    idPrefix: row.id,
                    isCopied: { $0 == copiedBlockId },
                    onCopy: { blockId in onCopy(blocks, blockId) }
                )
                if streaming {
                    RoundedRectangle(cornerRadius: 1)
                        .fill(Color.primary)
                        .frame(width: 8, height: 2)
                        .accessibilityIdentifier("chat.streaming")
                }
            }
        }
    }
}

/// The tail marker's bottom in the scroll view's own space.
private struct ThreadTailKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// The scroll view's height, read in the same space as the tail marker.
private struct ThreadViewportHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
