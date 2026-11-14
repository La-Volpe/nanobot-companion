import SwiftUI

/// The thread (plan.md I3): history, streaming answer, one composer row. The view draws
/// `ChatViewModel.rows` and calls one of two commands; every branch is in the model.
struct ChatView: View {
    @ObservedObject var model: ChatViewModel

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(model.rows) { row in
                            rowView(row)
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
            composer
        }
        .background(Color(.systemBackground))
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
                                .fill(Color(.secondarySystemBackground))
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
        case let .answer(text, streaming):
            VStack(alignment: .leading, spacing: 6) {
                Text(text)
                    .font(ReadingType.font(size: 17))
                    .lineSpacing(ReadingType.lineSpacing)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("chat.answer")
                if streaming {
                    RoundedRectangle(cornerRadius: 1)
                        .fill(Color.primary)
                        .frame(width: 8, height: 2)
                        .accessibilityIdentifier("chat.streaming")
                }
            }
        }
    }

    private var composer: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(alignment: .bottom, spacing: 10) {
                TextField(
                    "",
                    text: $model.draft,
                    prompt: Text("Message nanobot").foregroundColor(.secondary),
                    axis: .vertical
                )
                .lineLimit(1...5)
                .font(ReadingType.font(size: 17))
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                        .fill(Color(.secondarySystemBackground))
                )
                .accessibilityIdentifier("chat.composer")

                Button {
                    Task { await model.primaryAction() }
                } label: {
                    Image(systemName: model.showsStop ? "stop.fill" : "arrow.up")
                        .font(.system(size: 15, weight: .bold))
                        .frame(width: Design.minimumTarget, height: Design.minimumTarget)
                        .background(Color.primary, in: Circle())
                        .foregroundStyle(Color(.systemBackground))
                }
                .disabled(!model.showsStop && !model.canSend)
                .opacity(model.showsStop || model.canSend ? 1 : 0.4)
                .accessibilityIdentifier(model.showsStop ? "chat.stop" : "chat.send")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
    }
}
