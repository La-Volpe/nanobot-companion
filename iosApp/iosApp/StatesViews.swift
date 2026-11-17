import SwiftUI

/// The states surfaces: the connection dot in the bar and its banner, the notices above the
/// composer, the goal strip, the subtask dock and a delivery that failed. Every one of them
/// reads its value from `StatesViewModel`, which is the only owner of that state.
struct ConnectionDotView: View {
    let dot: ConnectionDot

    private var colour: Color {
        switch dot {
        case .open: .green
        case .connecting, .reconnecting: .orange
        case .offline, .failed: .red
        case .idle: .secondary
        }
    }

    var body: some View {
        Circle()
            .fill(colour)
            .frame(width: 6, height: 6)
            .accessibilityIdentifier("states.dot")
            .accessibilityLabel(label)
    }

    private var label: String {
        switch dot {
        case .open: "Connection: connected"
        case .connecting: "Connection: connecting"
        case .reconnecting: "Connection: reconnecting"
        case .offline: "Connection: disconnected"
        case .failed: "Connection: error"
        case .idle: "Connection: idle"
        }
    }
}

/// The banner under the bar (artifact: `#conn-banner`): one line, and a retry when the design
/// offers one.
struct ConnectionBannerView: View {
    let banner: ConnectionBanner
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            WebUIIcon(name: "Warning", size: 13)
            Text(banner.text)
                .font(.system(size: Design.Interface.captionSize))
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("states.banner")
            if banner.showsRetry {
                Button("Try again", action: onRetry)
                    .font(.system(size: Design.Interface.microSize, weight: .semibold))
                    .frame(minHeight: Design.minimumTarget)
                    .accessibilityIdentifier("states.retry")
            }
        }
        .foregroundStyle(.orange)
        .padding(.horizontal, 12)
        .background(Color.orange.opacity(0.12))
    }
}

/// One notice: a line, an optional second line, and the actions it offers.
struct StateRowView: View {
    let row: StateRow
    let onAction: (StateRow.Action) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: row.isError ? "exclamationmark.triangle" : "info.circle")
                .font(.system(size: 13))
                .foregroundStyle(row.isError ? Color.red : Color.secondary)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.text)
                    .font(.system(size: Design.Interface.captionSize))
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let detail = row.detail {
                    Text(detail)
                        .font(.system(size: Design.Interface.microSize))
                        .foregroundStyle(.secondary)
                }
            }
            ForEach(row.actions, id: \.identifier) { action in
                Button(action.title) { onAction(action) }
                    .font(.system(size: Design.Interface.microSize, weight: .semibold))
                    .frame(minHeight: Design.minimumTarget / 2)
                    .accessibilityIdentifier("states.action.\(action.identifier)")
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .fill(row.isError ? Color.red.opacity(0.1) : Palette.secondarySurface)
        )
    }
}

/// The goal strip above the composer: one line, the gateway's own summary.
struct GoalStripView: View {
    let text: String

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(Color.accentColor).frame(width: 6, height: 6)
            Text(text)
                .font(.system(size: Design.Interface.microSize, weight: .medium))
                .lineLimit(1)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("states.goal")
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
    }
}

/// The subtask dock above the composer: what the agent is doing in the background, with its
/// stop control.
struct SubagentDockView: View {
    let rows: [SubagentDockRow]
    let onStop: (String) -> Void

    var body: some View {
        ForEach(rows) { row in
            HStack(spacing: 9) {
                WebUIIcon(name: "FullAccess", size: 14)
                    .frame(width: 24, height: 24)
                    .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Subtask: \(row.label)")
                        .font(.system(size: Design.Interface.captionSize, weight: .semibold))
                        .lineLimit(1)
                    Text(row.caption)
                        .font(.system(size: Design.Interface.microSize))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Button {
                    onStop(row.id)
                } label: {
                    WebUIIcon(name: "Stop", size: 12)
                        .frame(width: Design.minimumTarget, height: Design.minimumTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Stop subtask")
                .accessibilityIdentifier("states.subagent.stop")
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                    .fill(Palette.secondarySurface)
            )
        }
    }
}

/// A message the gateway did not take: the row keeps the words and offers the only honest
/// affordance, sending it again (`ThreadComposer` shows the error kind, not a retry the
/// gateway does not have).
struct DeliveryRowView: View {
    let delivery: DeliveryFailure
    let onResend: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            WebUIIcon(name: "Warning", size: 12)
            Text(delivery.title)
                .font(.system(size: Design.Interface.microSize, weight: .semibold))
                .accessibilityIdentifier("states.delivery")
            Button(delivery.resending ? "Resending…" : "Resend", action: onResend)
                .font(.system(size: Design.Interface.microSize, weight: .semibold))
                .frame(minHeight: Design.minimumTarget / 2)
                .disabled(delivery.resending)
                .accessibilityIdentifier("states.delivery.resend")
        }
        .foregroundStyle(.red)
    }
}
