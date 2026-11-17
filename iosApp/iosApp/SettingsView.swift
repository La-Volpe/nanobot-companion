import SwiftUI

/// Settings: a grouped list that pushes into Overview, Notifications and
/// Appearance, with the gateway row as the way out. The view draws `SettingsViewModel`'s rows
/// and calls one command per tap.
struct SettingsView: View {
    @ObservedObject var model: SettingsViewModel
    @ObservedObject var holder: CoreHolder
    /// The open chat's model, handed down by the shell: Overview names this, never the
    /// gateway's configured default. Nil is "no chat open".
    let modelInUse: SettingsViewModel.CurrentModel?
    @Environment(\.dismiss) private var dismiss

    @State private var screen: Screen = .list
    @State private var confirmingDisconnect = false

    enum Screen: Equatable {
        case list
        case overview
        case notifications
        case appearance
    }

    var body: some View {
        NavigationStack {
            List {
                switch screen {
                case .list: listScreen
                case .overview: overviewScreen
                case .notifications: notificationsScreen
                case .appearance: appearanceScreen
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if screen == .list {
                        Button("Done") { dismiss() }
                            .accessibilityIdentifier("settings.done")
                    } else {
                        Button("Back") { screen = .list }
                            .accessibilityIdentifier("settings.back")
                    }
                }
            }
            .alert(model.disconnectTitle, isPresented: $confirmingDisconnect) {
                Button("Cancel", role: .cancel) {}
                Button("Disconnect", role: .destructive) {
                    Task {
                        await model.disconnect()
                        dismiss()
                    }
                }
            } message: {
                Text(model.disconnectMessage)
            }
        }
        .task {
            model.update(modelInUse: modelInUse)
            await refresh()
        }
        // A `/model` switch behind the sheet changes the model in use, and the row follows it
        // rather than describing the one the sheet was opened with.
        .onChange(of: modelInUse) { _, current in
            model.update(modelInUse: current)
        }
    }

    private var title: String {
        switch screen {
        case .list: "Settings"
        case .overview: "Overview"
        case .notifications: "Notifications"
        case .appearance: "Appearance"
        }
    }

    @ViewBuilder
    private var listScreen: some View {
        Section {
            Button {
                confirmingDisconnect = true
            } label: {
                SettingsRow(
                    title: "Gateway",
                    detail: model.gatewaySubtitle,
                    value: nil,
                    tint: model.gatewaySubtitle == "No gateway" ? .secondary : .green
                )
            }
            .accessibilityIdentifier("settings.gateway")
        }
        Section("Settings") {
            Button { screen = .overview } label: {
                SettingsRow(title: "Overview", detail: "Usage, version, server", value: nil, tint: nil)
            }
            .accessibilityIdentifier("settings.overview")
            Button { screen = .notifications } label: {
                SettingsRow(title: "Notifications", detail: "Finished tasks and failures", value: "On", tint: nil)
            }
            .accessibilityIdentifier("settings.notifications")
            Button { screen = .appearance } label: {
                SettingsRow(
                    title: "Appearance",
                    detail: "Theme and reading size",
                    value: model.theme.title,
                    tint: nil
                )
            }
            .accessibilityIdentifier("settings.appearance")
        }
        if let notice = model.notice {
            Section {
                Text(notice)
                    .font(.system(size: Design.Interface.microSize))
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var overviewScreen: some View {
        switch model.overview {
        case .loading:
            // The read has not answered: the shape of the rows it will draw, rather than rows
            // whose every value is blank.
            Section {
                SkeletonRows(count: 4, rowHeight: Design.minimumTarget, spacing: 2, label: "Loading gateway settings")
            }
        case .failed:
            Section {
                Text("Could not read the gateway settings.")
                    .font(.system(size: Design.Interface.captionSize))
                    .foregroundStyle(Palette.destructive)
                    .accessibilityIdentifier("settings.overview.failed")
            }
        case .loaded:
            // The card the design puts first (design of record, `.usage-card`): the total, the
            // gateway's own request count and cache rate, then one bar per day. It used to be
            // fetched and parsed into nothing.
            if let usage = model.snapshot.usage {
                Section {
                    TokenUsageCard(usage: usage)
                }
            }
            ForEach(model.overviewRows) { row in
                SettingsRow(title: row.label, detail: row.detail, value: row.value, tint: nil)
            }
        }
        Section {
            Text("Uptime is not part of the gateway settings payload, so it is not shown.")
                .font(.system(size: Design.Interface.microSize))
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("settings.noUptime")
        }
    }

    @ViewBuilder
    private var notificationsScreen: some View {
        Section {
            ForEach(model.notificationToggles) { toggle in
                Toggle(isOn: Binding(
                    get: { toggle.isOn },
                    set: { model.setToggle(toggle.id, on: $0) }
                )) {
                    Text(toggle.title).font(.system(size: Design.Interface.bodySize))
                }
                .frame(minHeight: Design.minimumTarget)
                .accessibilityIdentifier("settings.toggle.\(toggle.title)")
            }
        } header: {
            Text("Notify me when")
        }
        // The state the phone reports, and the one thing that can change it: the system dialog
        // while it can still be drawn, the phone's own screen after a "no".
        Section {
            SettingsRow(
                title: "System permission",
                detail: nil,
                value: model.permissionTitle,
                tint: nil
            )
            .frame(minHeight: Design.minimumTarget)
            .accessibilityIdentifier("settings.permission")
            // The sound is the reader's own choice, and it sits next to the state it modifies.
            Toggle(isOn: Binding(
                get: { model.notificationSound },
                set: { model.setNotificationSound($0) }
            )) {
                Text("Sound").font(.system(size: Design.Interface.bodySize))
            }
            .frame(minHeight: Design.minimumTarget)
            .accessibilityIdentifier("settings.notificationSound")
            if let action = model.permissionAction {
                Button {
                    Task { await model.applyPermissionChoice() }
                } label: {
                    SettingsRow(title: action, detail: nil, value: nil, tint: nil)
                }
                .accessibilityIdentifier("settings.permissionAction")
            }
            Button {
                Task { await model.sendTestNotification() }
            } label: {
                SettingsRow(title: "Send a test notification", detail: nil, value: nil, tint: nil)
            }
            .accessibilityIdentifier("settings.testNotification")
        }
        .task { await model.refreshPermission() }
        Section {
            Text(model.notificationsCopy)
                .font(.system(size: Design.Interface.microSize))
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("settings.notificationsCopy")
        }
    }

    @ViewBuilder
    private var appearanceScreen: some View {
        Section("Theme") {
            Picker("Appearance", selection: Binding(
                get: { model.theme },
                set: { model.setTheme($0) }
            )) {
                ForEach(SettingsViewModel.Theme.allCases, id: \.self) { theme in
                    Text(theme.title).tag(theme)
                }
            }
            .pickerStyle(.segmented)
            .frame(minHeight: Design.minimumTarget)
            .accessibilityIdentifier("settings.theme")
        }
        Section("Reading") {
            Picker("Text size", selection: Binding(
                get: { model.readingSize },
                set: { model.setReadingSize($0) }
            )) {
                ForEach(SettingsViewModel.ReadingSize.allCases, id: \.self) { size in
                    Text(size.title).tag(size)
                }
            }
            .pickerStyle(.segmented)
            .frame(minHeight: Design.minimumTarget)
            .accessibilityIdentifier("settings.readingSize")
            Text("Reading size changes messages, code and the composer. It never changes navigation or controls.")
                .font(.system(size: Design.Interface.microSize))
                .foregroundStyle(.secondary)
        }
    }

    private func refresh() async {
        // Nil is "the read did not answer": Overview says so instead of drawing blank values.
        await model.refresh {
            guard await holder.refreshSettings() else { return nil }
            return holder.settingsSnapshot
        }
    }
}

extension SettingsViewModel.Snapshot {
    /// What a scripted launch answers with: the dev gateway's own facts, so Overview's rows are
    /// the rows a connected app draws.
    static let scriptedGateway = SettingsViewModel.Snapshot(
        version: "0.4.1",
        commit: "28d73b69",
        os: "Darwin",
        pythonVersion: "3.12.9",
        transcriptionProvider: "groq",
        transcriptionModel: "whisper-large-v3",
        presets: ["default", "primary"],
        usage: .scripted
    )
}

extension SettingsViewModel.Usage {
    /// A scripted launch has no usage read, so the card is seeded with the dev gateway's own day
    /// (505 134 tokens). One full bar and 29 stubs is what that recording is: a tap proves the
    /// strip and the headline draw, which an all-zero window would not.
    static let scripted = SettingsViewModel.Usage(
        headline: "505K",
        totalTokens: 505_134,
        requests: 140,
        cacheHitPercent: 89,
        bars: (1...30).map { day in
            SettingsViewModel.Usage.Bar(
                date: String(format: "2026-11-%02d", day),
                height: day == 14 ? 1 : 0
            )
        },
        hasUsage: true
    )
}

extension SettingsViewModel.CurrentModel {
    /// A scripted launch has no composer flow, so Overview's model row is seeded: the chat's own
    /// preset and window, which is what the row must name.
    static let scriptedChat = SettingsViewModel.CurrentModel(
        name: "deepseek-v4.1-flash:cloud",
        preset: "primary",
        presetLabel: "Preset primary",
        windowLabel: "32.8K"
    )
}

/// Overview's token card, drawn as the design draws it: the 30-day total and its unit, the
/// gateway's own request count and cache hit rate, then a bar per day scaled to the window's peak
/// with the biggest day solid (design of record, `.spark i.hi`). Every number arrives computed and
/// worded by the core; this draws them and invents nothing.
struct TokenUsageCard: View {
    let usage: SettingsViewModel.Usage

    /// The exact number for a screen reader, which the WebUI also labels its headline with. Built
    /// as a `String` on purpose: an interpolated literal would reach `accessibilityLabel` as a
    /// `LocalizedStringKey`, and 505 134 would read "505.134" on a phone with a dot-grouping locale.
    private var exactLabel: String { "\(usage.totalTokens) tokens" }

    private var detail: String {
        var parts = ["\(usage.requests) requests"]
        if let percent = usage.cacheHitPercent { parts.append("\(percent)% cache hit rate") }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(usage.headline)
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(Palette.foreground)
                    .accessibilityLabel(exactLabel)
                    .accessibilityIdentifier("settings.usage.headline")
                Text("tokens · 30 days")
                    .font(.system(size: Design.Interface.captionSize))
                    .foregroundStyle(Palette.mutedForeground)
            }
            if usage.hasUsage {
                Text(detail)
                    .font(.system(size: Design.Interface.microSize))
                    .foregroundStyle(Palette.mutedForeground)
                    .accessibilityIdentifier("settings.usage.detail")
                HStack(alignment: .bottom, spacing: 2) {
                    ForEach(usage.bars) { bar in
                        // A day with no usage is the design's 2 pt stub, not a missing column: the
                        // strip is a calendar, and a gap in it would read as a gap in the data.
                        RoundedRectangle(cornerRadius: 2)
                            .fill(bar.height >= 1 ? Palette.foreground : Palette.foreground.opacity(0.26))
                            .frame(maxWidth: .infinity)
                            .frame(height: max(2, CGFloat(bar.height) * 38))
                    }
                }
                .frame(height: 38, alignment: .bottom)
                .accessibilityIdentifier("settings.usage.strip")
                .accessibilityLabel("Daily token usage, last 30 days")
            } else {
                Text("No token usage in the last 30 days.")
                    .font(.system(size: Design.Interface.microSize))
                    .foregroundStyle(Palette.mutedForeground)
                    .accessibilityIdentifier("settings.usage.empty")
            }
        }
        .padding(.vertical, 6)
        .frame(minHeight: Design.minimumTarget)
    }
}

/// One settings row: a title, a second line and a trailing value, at the 44 pt target.
struct SettingsRow: View {
    let title: String
    let detail: String?
    let value: String?
    let tint: Color?

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: Design.Interface.bodySize))
                if let detail {
                    Text(detail)
                        .font(.system(size: Design.Interface.microSize))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 4)
            if let value {
                Text(value)
                    .font(.system(size: Design.Interface.bodySize))
                    .foregroundStyle(tint ?? Color.secondary)
            }
        }
        .frame(minHeight: Design.minimumTarget)
        .contentShape(Rectangle())
    }
}
