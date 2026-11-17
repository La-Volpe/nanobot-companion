import SwiftUI
import UIKit

/// The thread: history, streaming answer, one composer row. The view draws
/// `ChatViewModel.rows` and calls one of two commands; every branch is in the model.
struct ChatView: View {
    @ObservedObject var model: ChatViewModel
    @ObservedObject var activity: ActivityViewModel
    @ObservedObject var composer: ComposerViewModel
    @ObservedObject var attachments: AttachmentViewModel
    @ObservedObject var voice: VoiceViewModel
    @ObservedObject var states: StatesViewModel
    /// The attach sheet wires the picker here; until then the button says it is not built yet.
    var onAttach: () -> Void = {}

    /// The scroll view's own height and the tail marker's position in the same space: the
    /// difference is the reader's distance from the end, which is what `ThreadViewport` decides on
    /// (webui `ThreadViewport.tsx:1002`).
    @State private var viewportHeight: CGFloat = 0
    @State private var tailBottom: CGFloat = 0

    /// The composer's field is the only focusable thing on this screen, so one flag is the whole
    /// keyboard's state: the field drives it, and the transcript clears it (owner report: there
    /// was no way to put the keyboard away).
    @FocusState private var composerFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            if let banner = states.banner {
                ConnectionBannerView(banner: banner) { states.retryConnection() }
            }
            conversation
                // A tap on the conversation, and not on a control inside it: the gesture runs
                // alongside the rows' own, so a tool row's disclosure, a reasoning fold and a
                // copy button still do their job (`KeyboardTests`). It sits here rather than on
                // the scroll view because a new chat has no scroll view, and an empty thread is
                // exactly where the keyboard would otherwise be stranded.
                .contentShape(Rectangle())
                .simultaneousGesture(TapGesture().onEnded { composerFocused = false })
            composerZone
        }
        .background(Palette.canvas)
    }

    @ViewBuilder
    private var conversation: some View {
        switch model.placeholder {
        case .skeleton:
            ThreadSkeletonView()
        case .failed:
            ThreadReadFailedView()
        case .empty:
            EmptyThreadView()
        case .none:
            threadScroll
        }
    }

    /// The conversation itself: history, the streaming answer, the delivery row.
    private var threadScroll: some View {
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
                // The flick every iPhone user tries: the keyboard follows the transcript down and
                // leaves with it. `.immediately` would take it away on the first pixel of a scroll,
                // which turns reading up-thread into a keyboard dismissal.
                .scrollDismissesKeyboard(.interactively)
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
            ComposerBar(
                model: composer,
                attachments: attachments,
                voice: voice,
                focus: $composerFocused,
                onAttach: onAttach
            )
        }
    }
}

/// The conversation that has not arrived: a bubble and two answers' worth of lines, at their sizes
/// (the WebUI's `ThreadLoadingSkeleton`).
struct ThreadSkeletonView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Spacer(minLength: 100)
                SkeletonLine(width: nil, height: 40, corner: Design.Radius.bubble)
                    .frame(maxWidth: .infinity)
            }
            SkeletonLine(width: nil, height: 12).padding(.trailing, 60)
            SkeletonLine(width: nil, height: 12)
            SkeletonLine(width: nil, height: 12).padding(.trailing, 130)
            SkeletonLine(width: nil, height: 12).padding(.trailing, 90)
            SkeletonLine(width: nil, height: 12).padding(.trailing, 180)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("chat.skeleton")
        .accessibilityLabel("Loading conversation")
    }
}

/// A page read that did not answer: the app's problem, ended in a sentence, not a spinner.
struct ThreadReadFailedView: View {
    var body: some View {
        Text("Could not read this conversation from the gateway.")
            .font(ReadingType.font(size: 15))
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("chat.readFailed")
    }
}

/// A conversation the gateway really has no turns for. iOS had no empty copy at all before this:
/// a new chat was simply blank, which is the same screen as a slow one.
struct EmptyThreadView: View {
    var body: some View {
        VStack(spacing: 6) {
            Text("Nothing here yet")
                .font(ReadingType.font(size: 17))
                .foregroundStyle(.primary)
            Text("Send the first message to start this conversation.")
                .font(ReadingType.font(size: 15))
                .foregroundStyle(.secondary)
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("chat.empty")
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
        case let .media(media):
            MediaRowView(row: media)
        case let .answer(blocks, streaming):
            VStack(alignment: .leading, spacing: 6) {
                ReadingBlocksView(
                    blocks: blocks,
                    idPrefix: row.id,
                    streaming: streaming,
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
