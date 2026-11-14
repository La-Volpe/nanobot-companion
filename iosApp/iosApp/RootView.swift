import SwiftUI

/// The app's screen: onboarding, then the shell that holds the drawer and (from I3) the thread.
struct RootView: View {
    let container: Container

    @ObservedObject private var holder: CoreHolder
    @StateObject private var connect: ConnectViewModel
    @StateObject private var drawer: DrawerViewModel

    @State private var connected = false
    @State private var drawerOpen = false
    @State private var selected: SessionSummary?
    @StateObject private var chat: ChatViewModel
    @State private var chatDriver: CoreChat?

    init(container: Container) {
        self.container = container
        self.holder = container.holder
        _connect = StateObject(
            wrappedValue: ConnectViewModel(gateway: container.gateway, credentials: container.credentials)
        )
        _connected = State(initialValue: container.startsConnected)
        _chat = StateObject(wrappedValue: ChatViewModel(chat: NoopChat()))
        // A scripted launch seeds the rows before the view exists, so the drawer starts from
        // what the holder already has rather than waiting for a change that never comes.
        _drawer = StateObject(
            wrappedValue: DrawerViewModel(rows: container.holder.sessions, loaded: container.holder.sessionsLoaded)
        )
    }

    var body: some View {
        Group {
            if connected {
                shell
            } else {
                ConnectView(model: connect) { connected = true }
            }
        }
        .onChange(of: holder.sessions) { _, rows in
            drawer.update(rows: rows, loaded: holder.sessionsLoaded)
        }
        .onChange(of: holder.sessionsLoaded) { _, loaded in
            drawer.update(rows: holder.sessions, loaded: loaded)
        }
    }

    private var shell: some View {
        NavigationStack {
            ZStack(alignment: .leading) {
                ChatView(model: chat)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button {
                                withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) { drawerOpen = true }
                            } label: {
                                Image(systemName: "line.3.horizontal")
                            }
                            .accessibilityIdentifier("chat.openDrawer")
                        }
                    }
                    .navigationTitle(selected?.displayTitle ?? "nanobot")
                    .navigationBarTitleDisplayMode(.inline)
                    .task {
                        try? await holder.refreshSessions()
                    }
                    .task { await openChat() }

                if drawerOpen {
                    Color.black.opacity(0.22)
                        .ignoresSafeArea()
                        .transition(.opacity)
                        .onTapGesture { closeDrawer() }
                        .accessibilityIdentifier("drawer.scrim")
                    DrawerView(
                        model: drawer,
                        selectedKey: selected?.key,
                        onSelect: { row in
                            selected = row
                            closeDrawer()
                            Task { await openChat() }
                        },
                        onNewChat: {
                            selected = nil
                            closeDrawer()
                            Task { await openChat() }
                        },
                        onAutomations: closeDrawer,
                        onSettings: closeDrawer
                    )
                    .frame(width: 310)
                    .transition(.move(edge: .leading))
                    .shadow(color: .black.opacity(0.18), radius: 18, x: 6)
                }
            }
        }
    }

    /// Opening a chat: one attach, and the gateway replays the history into the same thread
    /// the live socket fills (D9).
    private func openChat() async {
        chatDriver?.stopObserving()
        let driver: ChatDriving
        #if DEBUG
        if container.scripted {
            let scripted = ScriptedChat { state in chat.update(thread: state) }
            chatDriver = nil
            chat.attach(scripted)
            await scripted.open()
            return
        }
        #endif
        guard let handle = holder.chat(id: selected?.chatId) else { return }
        let live = CoreChat(holder: holder, handle: handle) { state in
            chat.update(thread: state)
        }
        live.start()
        chatDriver = live
        driver = live
        chat.attach(driver)
        await driver.open()
    }

    private func closeDrawer() {
        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) { drawerOpen = false }
    }

    private func disconnect() {
        connected = false
        selected = nil
        connect.clearMessage()
        container.gateway.disconnect()
        chatDriver?.stopObserving()
        chatDriver = nil
    }
}

/// What I3 replaces with the thread. It says what it is rather than pretending to be a chat.
struct ChatShellPlaceholder: View {
    let disconnect: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("Connected").font(Design.Interface.body)
            Text("The thread arrives in the next milestone.")
                .font(ReadingType.font(size: 15))
                .foregroundStyle(.secondary)
            Button("Disconnect", action: disconnect)
                .frame(minHeight: Design.minimumTarget)
                .accessibilityIdentifier("chat.disconnect")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}
