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

    var body: some View {
        VStack(spacing: 0) {
            if let banner = states.banner {
                ConnectionBannerView(banner: banner) { states.retryConnection() }
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(model.rows) { row in
                            rowView(row)
                        }
                        ForEach(states.rows) { row in
                            StateRowView(row: row) { action in states.perform(action, in: row) }
                        }
                        if let delivery = states.delivery {
                            DeliveryRowView(delivery: delivery) {
                                Task { await states.resend() }
                            }
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .onChange(of: model.rows) { _, _ in
                    // Follow the answer while it arrives, which is what a reader expects.
                    withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo("bottom", anchor: .bottom) }
                }
            }
            composerZone
        }
        .background(Palette.canvas)
    }

    @ViewBuilder
    private func rowView(_ row: ChatRow) -> some View {
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
        case let .activity(turnId):
            if let cluster = activity.cluster(for: turnId) {
                ActivityClusterView(cluster: cluster) { activity.toggle(turnId: turnId) }
            }
        case let .answer(blocks, streaming):
            VStack(alignment: .leading, spacing: 6) {
                ReadingBlocksView(
                    blocks: blocks,
                    idPrefix: row.id,
                    isCopied: { composer.isCopied($0) },
                    onCopy: { blockId in
                        UIPasteboard.general.string = blocks.map(\.plainText).joined(separator: "\n")
                        composer.copy(blockId: blockId)
                    }
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
