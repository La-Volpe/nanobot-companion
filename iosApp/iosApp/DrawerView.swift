import SwiftUI

/// The session drawer (plan.md I2): brand, search, the session list, the archived row and the
/// automations entry pinned at the bottom. The view reads `DrawerViewModel` and calls two
/// commands; nothing here branches on data.
struct DrawerView: View {
    @ObservedObject var model: DrawerViewModel
    let selectedKey: String?
    let onSelect: (SessionSummary) -> Void
    let onNewChat: () -> Void
    let onAutomations: () -> Void
    let onSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            brand
            search
            content
            footer
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Color(.secondarySystemBackground))
    }

    private var brand: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(Color.primary)
                .frame(width: 26, height: 26)
                .overlay(Text("n").font(.system(size: 15, weight: .bold)).foregroundStyle(Color(.secondarySystemBackground)))
            Text("nanobot")
                .font(.system(size: 17, weight: .semibold))
            Spacer()
            Button(action: onNewChat) {
                Image(systemName: "square.and.pencil")
                    .frame(width: Design.minimumTarget, height: Design.minimumTarget)
            }
            .accessibilityIdentifier("drawer.newChat")
            Button(action: onSettings) {
                Image(systemName: "gearshape")
                    .frame(width: Design.minimumTarget, height: Design.minimumTarget)
            }
            .accessibilityIdentifier("drawer.settings")
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.top, 8)
    }

    private var search: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
            TextField("Search sessions", text: $model.searchText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("drawer.search")
            if model.isSearching {
                Button {
                    model.searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .accessibilityIdentifier("drawer.clearSearch")
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 36)
        .background(Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    @ViewBuilder
    private var content: some View {
        if let message = model.emptyMessage {
            VStack(spacing: 6) {
                Text(message)
                    .font(ReadingType.font(size: 15))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("drawer.emptyState")
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.vertical, 28)
        }

        ScrollView {
            LazyVStack(alignment: .leading, spacing: 2) {
                ForEach(model.state.rowsOrEmpty) { row in
                    Button {
                        onSelect(row)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.displayTitle)
                                .font(.system(size: 15, weight: .medium))
                                .lineLimit(1)
                            if !row.preview.isEmpty {
                                Text(row.preview)
                                    .font(.system(size: 13))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                        .frame(minHeight: Design.minimumTarget)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(row.key == selectedKey ? Color.primary.opacity(0.10) : .clear)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("drawer.row.\(row.key)")
                }

                archivedRow
                Spacer(minLength: 8)
            }
            .padding(.horizontal, 8)
        }
    }

    private var archivedRow: some View {
        Button {
            model.showsArchived.toggle()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "archivebox")
                    .font(.system(size: 13))
                Text("Archived")
                    .font(.system(size: 15))
                Spacer()
                Image(systemName: model.showsArchived ? "chevron.down" : "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 12)
            .frame(minHeight: Design.minimumTarget)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("drawer.archived")
    }

    private var footer: some View {
        VStack(spacing: 0) {
            Divider()
            Button(action: onAutomations) {
                HStack(spacing: 8) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 14))
                    Text("Automations")
                        .font(.system(size: 15))
                    Spacer()
                }
                .padding(.horizontal, 16)
                .frame(minHeight: Design.minimumTarget)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("drawer.automations")
        }
    }
}

private extension DrawerViewModel.ListState {
    /// Rows to draw; the three non-list states draw none.
    var rowsOrEmpty: [SessionSummary] {
        if case let .rows(rows) = self { return rows }
        return []
    }
}
