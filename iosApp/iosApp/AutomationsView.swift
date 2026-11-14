import SwiftUI

/// The automations page (I2b), following the design's `data-view="automations"`: a short title
/// bar, a "Scheduled" group of cards, the sentence that says where these come from, and one
/// button that asks the agent to create another. Each card opens the design's action sheet.
struct AutomationsView: View {
    @ObservedObject var model: AutomationsViewModel
    let onClose: () -> Void
    let onAskNanobot: () -> Void

    @State private var actionsFor: AutomationRow?
    @State private var confirmingDelete: AutomationRow?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Scheduled")
                        .font(.system(size: Design.Interface.microSize, weight: .semibold))
                        .textCase(.uppercase)
                        .tracking(0.6)
                        .foregroundStyle(Palette.mutedForeground)
                        .padding(.top, 4)
                        .padding(.bottom, 8)

                    if model.isEmpty {
                        Text("No automations yet. Ask nanobot in a chat and it schedules one.")
                            .font(.system(size: Design.Interface.captionSize))
                            .foregroundStyle(Palette.mutedForeground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 14)
                            .accessibilityIdentifier("automations.empty")
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(model.rows.enumerated()), id: \.element.id) { index, row in
                                if index > 0 { Divider().padding(.leading, 46) }
                                card(row)
                            }
                        }
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: Design.Radius.bubble, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: Design.Radius.bubble, style: .continuous)
                                .strokeBorder(Palette.border, lineWidth: 1)
                        )
                    }

                    if let message = model.message {
                        Text(message)
                            .font(.system(size: Design.Interface.captionSize))
                            .foregroundStyle(Palette.destructive)
                            .padding(.top, 10)
                            .accessibilityIdentifier("automations.message")
                    }

                    Text("nanobot creates these when you ask in a chat. This page lists, runs, pauses and deletes them; it does not invent schedules behind your back.")
                        .font(.system(size: Design.Interface.microSize))
                        .foregroundStyle(Palette.mutedForeground)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 12)

                    Button(action: onAskNanobot) {
                        HStack(spacing: 8) {
                            WebUIIcon(name: "Conversation", size: 16)
                            Text("Ask nanobot to create one")
                                .font(.system(size: 15, weight: .semibold))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background(Palette.primary, in: RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous))
                        .foregroundStyle(Palette.onPrimary)
                    }
                    .padding(.top, 14)
                    .accessibilityIdentifier("automations.ask")
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(Palette.canvas)
            .navigationTitle("Automations")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onClose) {
                        WebUIIcon(name: "ChevronRight", size: 15)
                            .rotationEffect(.degrees(180))
                    }
                    .accessibilityLabel("Back")
                    .accessibilityIdentifier("automations.back")
                }
            }
            .task { if !model.loaded { await model.load() } }
            .confirmationDialog(
                actionsFor?.name ?? "",
                isPresented: Binding(get: { actionsFor != nil }, set: { if !$0 { actionsFor = nil } }),
                titleVisibility: .visible,
                presenting: actionsFor
            ) { row in
                if row.enabled && !row.isSystem {
                    Button("Run now") {
                        actionsFor = nil
                        Task { await model.runNow(row.id) }
                    }
                    .accessibilityIdentifier("automation.run")
                }
                Button(row.enabled ? "Pause" : "Resume") {
                    actionsFor = nil
                    Task { await model.setEnabled(row.id, !row.enabled) }
                }
                .accessibilityIdentifier("automation.toggle")
                Button("Delete automation", role: .destructive) {
                    actionsFor = nil
                    confirmingDelete = row
                }
                .accessibilityIdentifier("automation.delete")
                Button("Cancel", role: .cancel) { actionsFor = nil }
            }
            .alert(
                "Delete this automation?",
                isPresented: Binding(get: { confirmingDelete != nil }, set: { if !$0 { confirmingDelete = nil } }),
                presenting: confirmingDelete
            ) { row in
                Button("Delete", role: .destructive) {
                    confirmingDelete = nil
                    Task { await model.delete(row.id) }
                }
                Button("Keep", role: .cancel) { confirmingDelete = nil }
            } message: { row in
                Text("“\(row.name)” and its run history are removed from the gateway.")
            }
        }
    }

    /// The design's card: glyph, name, schedule, what it is doing, and either a switch or the
    /// System flag — never a switch that the gateway would answer with a 403.
    private func card(_ row: AutomationRow) -> some View {
        Button {
            actionsFor = row
        } label: {
            HStack(spacing: 10) {
                WebUIIcon(name: "Automations", size: 18)
                    .foregroundStyle(Palette.mutedForeground)
                    .frame(width: 26, alignment: .leading)

                VStack(alignment: .leading, spacing: 1) {
                    Text(row.name)
                        .font(.system(size: 15))
                        .foregroundStyle(Palette.foreground)
                        .lineLimit(1)
                    Text(row.schedule)
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.mutedForeground)
                        .lineLimit(1)
                    Text(row.detail)
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.mutedForeground)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)

                if row.isSystem {
                    Text("System")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Palette.mutedForeground)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Palette.secondarySurface, in: Capsule())
                } else {
                    Toggle("", isOn: Binding(
                        get: { row.enabled },
                        set: { on in Task { await model.setEnabled(row.id, on) } }
                    ))
                    .labelsHidden()
                    .tint(Palette.primary)
                    .disabled(row.pending)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("automation.\(row.id)")
    }
}
