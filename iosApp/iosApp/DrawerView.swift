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
        .background(Palette.sidebar)
    }

    private var brand: some View {
        HStack(spacing: 10) {
            NanobotMark(height: 30)
            Text("nanobot")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Palette.foreground)
            Spacer()
            Button(action: onNewChat) {
                WebUIIcon(name: "NewChat", size: 19)
                    .frame(width: Design.minimumTarget, height: Design.minimumTarget)
            }
            .accessibilityIdentifier("drawer.newChat")
            Button(action: onSettings) {
                WebUIIcon(name: "Settings", size: 19)
                    .frame(width: Design.minimumTarget, height: Design.minimumTarget)
            }
            .accessibilityIdentifier("drawer.settings")
        }
        .foregroundStyle(Palette.sidebarForeground)
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.top, 8)
    }

    private var search: some View {
        HStack(spacing: 8) {
            WebUIIcon(name: "Search", size: 14)
                .foregroundStyle(Palette.mutedForeground)
            TextField("Search sessions", text: $model.searchText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("drawer.search")
            if model.isSearching {
                Button {
                    model.searchText = ""
                } label: {
                    WebUIIcon(name: "Close", size: 14).foregroundStyle(Palette.mutedForeground)
                }
                .accessibilityIdentifier("drawer.clearSearch")
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 36)
        .background(Palette.secondarySurface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    @ViewBuilder
    private var content: some View {
        // A read in flight draws the shape of the rows it will have. Before this the drawer was
        // simply blank until `GET /api/sessions` answered, which reads exactly like "no sessions".
        if model.state == .loading {
            SkeletonRows(count: 5, rowHeight: 44, label: "Loading conversations")
                .padding(.horizontal, 8)
                .padding(.top, 4)
        }

        if let message = model.failureMessage {
            Text(message)
                .font(ReadingType.font(size: 15))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.vertical, 28)
                .accessibilityIdentifier("drawer.failureState")
        }

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
                WebUIIcon(name: "Archive", size: 15)
                Text("Archived")
                    .font(.system(size: 15))
                Spacer()
                WebUIIcon(name: model.showsArchived ? "ChevronDown" : "ChevronRight", size: 12)
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
                    WebUIIcon(name: "Automations", size: 16)
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
