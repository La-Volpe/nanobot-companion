import SwiftUI

/// The composer zone (I5): the chips above the field, the meta row (access, context ring,
/// model), the field, and the one button whose job the model decides.
///
/// Layout follows the artifact: `.quoted-chip`/`.queue-chip` above `.meta-row`, then
/// `.composer` with the attach button on the left and Send/Stop on the right. Every control is
/// at least 44 pt (design README's iOS column).
struct ComposerBar: View {
    @Environment(\.readingSize) private var readingSize
    @ObservedObject var model: ComposerViewModel
    @ObservedObject var attachments: AttachmentViewModel
    @ObservedObject var voice: VoiceViewModel
    let onAttach: () -> Void

    /// The press in progress, as one number so the gesture and the button agree on it.
    @State private var holdStartedAt: Int?
    /// Set while a hold owns the press; the Button's action is the release, which the gesture
    /// has already handled (the artifact's `suppressClick`).
    @State private var suppressClick = false

    var body: some View {
        VStack(spacing: 6) {
            if let queued = model.snapshot.queued, !queued.isEmpty {
                QueueChip(text: queued, onSendNow: { Task { await model.sendQueuedNow() } }, onDelete: model.deleteQueued)
            }
            if !attachments.attachments.isEmpty {
                AttachmentStrip(model: attachments)
            }
            if voice.phase == .recording {
                VoiceMeter(elapsed: voice.elapsedLabel)
            }
            metaRow
            field
            if let notice = voice.notice {
                Text(notice)
                    .font(.system(size: Design.Interface.microSize))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("voice.notice")
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 6)
        .padding(.bottom, Design.composerBottomPadding)
        .background(Palette.canvas)
    }

    private var metaRow: some View {
        HStack(spacing: 8) {
            Button {
                model.openAccessSheet()
            } label: {
                Image(systemName: model.snapshot.access == .full ? "exclamationmark.shield.fill" : "shield")
                    .font(.system(size: 13))
                    .frame(width: Design.minimumTarget, height: Design.minimumTarget)
                    .contentShape(Rectangle())
                    .foregroundStyle(model.snapshot.access == .full ? Design.brand : Color.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(model.snapshot.access == .full ? "Workspace access: full" : "Workspace access: default")
            .accessibilityIdentifier("composer.access")

            Spacer(minLength: 0)

            HStack(spacing: 8) {
                Button {
                    model.openUsageSheet()
                } label: {
                    HStack(spacing: 5) {
                        UsageRing(percent: model.usagePercent)
                        Text(model.usagePercent.map { "\($0)%" } ?? "—")
                            .font(.system(size: Design.Interface.microSize))
                            .foregroundStyle(.secondary)
                    }
                    .frame(minWidth: Design.minimumTarget, minHeight: Design.minimumTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("composer.usage")

                Rectangle().fill(Color.secondary.opacity(0.3)).frame(width: 1, height: 14)

                Button {
                    model.openModelSheet()
                } label: {
                    HStack(spacing: 4) {
                        Text(model.modelLabel)
                            .font(.system(size: Design.Interface.bodySize))
                            .lineLimit(1)
                        WebUIIcon(name: "ChevronDown", size: 10)
                    }
                    .foregroundStyle(.primary)
                    .frame(minHeight: Design.minimumTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("composer.model")
            }
            .padding(.horizontal, 8)
            .background(
                Capsule().fill(Palette.secondarySurface)
            )
        }
    }

    private var primary: ComposerViewModel.PrimaryState {
        model.primaryState(recording: voice.phase == .recording)
    }

    /// The WebUI's own glyphs, not SF Symbols: the composer's stop and send are the buttons
    /// the browser draws (`product-icons.tsx`).
    private func primaryGlyph(_ state: ComposerViewModel.PrimaryState) -> String {
        switch state {
        case .voice: "Voice"
        case .send: "Send"
        case .stop: "Stop"
        }
    }

    private var field: some View {
        HStack(alignment: .bottom, spacing: 8) {
            Button(action: onAttach) {
                WebUIIcon(name: "Attach", size: 18)
                    .frame(width: Design.minimumTarget, height: Design.minimumTarget)
                    .contentShape(Rectangle())
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Attach files")
            .accessibilityIdentifier("composer.attach")

            TextField(
                "",
                text: $model.draft,
                prompt: Text("Type a message…").foregroundColor(.secondary),
                axis: .vertical
            )
            .lineLimit(1...5)
            .font(ReadingType.font(size: readingSize))
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                    .fill(Palette.secondarySurface)
            )
            .accessibilityIdentifier("chat.composer")
            .onChange(of: model.draft) { _, _ in
                Task { await model.refreshPalette() }
            }

            primaryButton
        }
    }

    /// One button, three jobs: hold it to record, tap it to send, tap it to stop. Which one it is
    /// is `ComposerViewModel.primaryState`, not a branch here.
    private var primaryButton: some View {
        Button {
            if suppressClick {
                suppressClick = false
                return
            }
            Task { await primaryAction() }
        } label: {
            WebUIIcon(name: primaryGlyph(primary), size: 19)
                .frame(width: Design.minimumTarget, height: Design.minimumTarget)
                .background(Palette.primary, in: Circle())
                .foregroundStyle(Palette.onPrimary)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier(primary))
        .accessibilityLabel(label(primary))
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard primary == .voice, holdStartedAt == nil else { return }
                    suppressClick = true
                    let now = Self.nowMs()
                    holdStartedAt = now
                    voice.beginHold(nowMs: now)
                }
                .onEnded { _ in
                    guard let started = holdStartedAt else { return }
                    holdStartedAt = nil
                    let now = Self.nowMs()
                    Task {
                        if now - started < Design.tapThresholdMs {
                            // A short press arms tap-to-dictate and keeps recording.
                            voice.armTapMode()
                        } else {
                            await voice.endHold(nowMs: now)
                        }
                        await applyTranscript()
                        suppressClick = false
                    }
                }
        )
    }

    private func identifier(_ state: ComposerViewModel.PrimaryState) -> String {
        switch state {
        case .voice: "chat.voice"
        case .send: "chat.send"
        case .stop: "chat.stop"
        }
    }

    private func label(_ state: ComposerViewModel.PrimaryState) -> String {
        switch state {
        case .voice: "Voice input"
        case .send: "Send message"
        case .stop: "Stop"
        }
    }

    private func primaryAction() async {
        if primary == .stop, voice.phase == .recording {
            await voice.tap(nowMs: Self.nowMs())
            await applyTranscript()
            return
        }
        // Stop, queue and send are all the model's; so is what the send carries.
        await model.primaryAction()
    }

    /// The transcript lands after whatever was typed; the core appends it to the draft (C6.5).
    private func applyTranscript() async {
        guard let text = voice.pendingTranscript, !text.isEmpty else { return }
        model.draft += (model.draft.isEmpty ? "" : " ") + text
    }

    private static func nowMs() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }
}

/// The prompt waiting for the running turn: Send now steers, Delete drops it (design
/// `.queue-chip`).
struct QueueChip: View {
    let text: String
    let onSendNow: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(text)
                .font(.system(size: 11.5))
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("composer.queue")
            Button("Send now", action: onSendNow)
                .font(.system(size: 10.5, weight: .semibold))
                .frame(minHeight: Design.minimumTarget / 2)
                .accessibilityIdentifier("composer.queue.sendNow")
            Button("Delete", action: onDelete)
                .font(.system(size: 10.5, weight: .semibold))
                .frame(minHeight: Design.minimumTarget / 2)
                .accessibilityIdentifier("composer.queue.delete")
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .fill(Palette.secondarySurface)
        )
    }
}

/// The context ring: `--pct` in the artifact, a stroked circle here.
struct UsageRing: View {
    let percent: Int?

    private var fraction: Double { Double(percent ?? 0) / 100 }

    var body: some View {
        ZStack {
            Circle().stroke(Color.secondary.opacity(0.25), lineWidth: 2)
            Circle()
                .trim(from: 0, to: fraction)
                .stroke(Color.primary, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 13, height: 13)
    }
}

/// The sheets the composer opens: model presets, the Default/Full access choice, and context
/// usage. One `switch` on the model's sheet value, so a sheet cannot be half-open.
struct ComposerSheets: View {
    @ObservedObject var model: ComposerViewModel
    @ObservedObject var attachments: AttachmentViewModel
    let loadPresets: () async -> [ChatModelPreset]
    let onPickPhotos: () -> Void
    let onPickFiles: () -> Void

    var body: some View {
        switch model.sheet {
        case .model:
            sheet(title: "Choose model") {
                ForEach(model.presets) { preset in
                    SheetRow(
                        title: preset.name,
                        subtitle: preset.subtitle,
                        checked: preset.active,
                        identifier: "composer.model.\(preset.name)"
                    ) {
                        Task { await model.pick(preset: preset) }
                    }
                }
                SheetRow(
                    title: "Manage presets",
                    subtitle: "Providers and fallbacks in Settings",
                    checked: false,
                    identifier: "composer.model.manage"
                ) {
                    model.closeSheet()
                }
            }
            .task { await model.loadPresets(from: loadPresets) }
        case .access:
            sheet(title: "Workspace access") {
                SheetRow(
                    title: "Default",
                    subtitle: "Files and commands inside the working directory",
                    checked: model.snapshot.access == .default,
                    identifier: "composer.access.default"
                ) {
                    Task { await model.pick(access: .default) }
                }
                SheetRow(
                    title: "Full access",
                    subtitle: "Files and commands outside the working directory",
                    checked: model.snapshot.access == .full,
                    identifier: "composer.access.full"
                ) {
                    Task { await model.pick(access: .full) }
                }
            }
        case .usage:
            sheet(title: "Context usage") {
                SheetRow(
                    title: model.usagePercent.map { "\($0)% of the window" } ?? "No window yet",
                    subtitle: usageSubtitle,
                    checked: false,
                    identifier: "composer.usage.row"
                ) {
                    model.closeSheet()
                }
            }
        case .palette:
            paletteSheet
        case .attach:
            AttachSheet(model: attachments, onPickPhotos: onPickPhotos, onPickFiles: onPickFiles)
        case nil:
            EmptyView()
        }
    }

    private var usageSubtitle: String {
        guard let window = model.snapshot.contextWindowTokens else {
            return "The gateway reports this with the first model update of a turn."
        }
        let used = model.snapshot.usedTokens ?? 0
        return "\(used) of \(window) tokens"
    }

    private var paletteSheet: some View {
        sheet(title: model.palette?.title ?? "Commands") {
            ForEach(model.palette?.grouped ?? [], id: \.0) { group, rows in
                Text(group.uppercased())
                    .font(.system(size: Design.Interface.microSize, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 6)
                ForEach(rows) { row in
                    SheetRow(title: row.label, subtitle: row.subtitle, checked: false, identifier: "composer.palette.\(row.id)") {
                        model.pick(row: row)
                    }
                }
            }
        }
    }

    private func sheet<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: Design.Interface.captionSize, weight: .semibold))
                .foregroundStyle(.secondary)
            content()
            Spacer(minLength: 0)
        }
        .padding(16)
    }
}

/// One row of a sheet: label, second line, and a checkmark when it is the current choice.
struct SheetRow: View {
    let title: String
    let subtitle: String
    let checked: Bool
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: Design.Interface.bodySize))
                    Text(subtitle)
                        .font(.system(size: Design.Interface.microSize))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                if checked {
                    WebUIIcon(name: "Check", size: 12)
                }
            }
            .frame(minHeight: Design.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }
}

/// The recording meter: the elapsed clock and the bars (artifact: `#voice-meter`). The bars are
/// a level indicator over the real recorder, so they move while the microphone is open.
struct VoiceMeter: View {
    let elapsed: String
    @State private var phase = false

    var body: some View {
        HStack(spacing: 10) {
            Text(elapsed)
                .font(.system(size: Design.Interface.microSize, design: .monospaced))
                .foregroundStyle(.red)
            HStack(spacing: 2) {
                ForEach(0..<20, id: \.self) { index in
                    Capsule()
                        .fill(Palette.destructive.opacity(0.7))
                        .frame(width: 2, height: barHeight(index))
                }
            }
            .animation(.easeInOut(duration: 0.35).repeatForever(autoreverses: true), value: phase)
            .onAppear { phase = true }
            Spacer(minLength: 0)
            Text("Tap to stop")
                .font(.system(size: Design.Interface.microSize))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("voice.meter")
    }

    private func barHeight(_ index: Int) -> CGFloat {
        let base = CGFloat(4 + (index % 5) * 2)
        return phase ? base + 10 : base
    }
}
