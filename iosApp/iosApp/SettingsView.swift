import SwiftUI

/// Settings (plan.md I7): a grouped list that pushes into Overview, Notifications and
/// Appearance, with the gateway row as the way out. The view draws `SettingsViewModel`'s rows
/// and calls one command per tap.
struct SettingsView: View {
    @ObservedObject var model: SettingsViewModel
    @ObservedObject var holder: CoreHolder
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
        .task { await refresh() }
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
        Section {
            SettingsRow(
                title: "nanobot Gateway",
                detail: model.snapshot.commit.map { "Commit \($0)" },
                value: model.snapshot.version,
                tint: nil
            )
        } header: {
            Text("About")
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
        ForEach(model.overviewRows) { row in
            SettingsRow(title: row.label, detail: row.detail, value: row.value, tint: nil)
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
        Section {
            Button {
                Task { await model.sendTestNotification() }
            } label: {
                SettingsRow(title: "Send a test notification", detail: nil, value: nil, tint: nil)
            }
            .accessibilityIdentifier("settings.testNotification")
        }
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
        await holder.refreshSettings()
        model.update(snapshot: holder.settingsSnapshot)
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
