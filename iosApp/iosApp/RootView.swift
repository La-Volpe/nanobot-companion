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
    @StateObject private var activity: ActivityViewModel
    @StateObject private var composer: ComposerViewModel
    @StateObject private var attachments: AttachmentViewModel
    @StateObject private var voice: VoiceViewModel
    @StateObject private var settings: SettingsViewModel
    @StateObject private var states: StatesViewModel
    @State private var debugOpen = false
    @State private var settingsOpen = false
    @State private var photosPresented = false
    @State private var filesPresented = false
    @State private var chatDriver: CoreChat?

    init(container: Container) {
        self.container = container
        self.holder = container.holder
        _connect = StateObject(
            wrappedValue: ConnectViewModel(
                gateway: container.gateway,
                credentials: container.credentials,
                prefilledKey: container.prefilledKey
            )
        )
        _connected = State(initialValue: container.startsConnected)
        _chat = StateObject(wrappedValue: ChatViewModel(chat: NoopChat()))
        _activity = StateObject(wrappedValue: ActivityViewModel())
        _composer = StateObject(wrappedValue: ComposerViewModel(driver: NoopComposer()))
        _attachments = StateObject(wrappedValue: AttachmentViewModel(driver: NoopAttachments()))
        _voice = StateObject(wrappedValue: VoiceViewModel(recording: MicrophoneRecorder(), driver: NoopVoice()))
        let credentials = container.credentials
        _states = StateObject(wrappedValue: StatesViewModel())
        _settings = StateObject(
            wrappedValue: SettingsViewModel(
                credentials: credentials,
                notifier: LocalNotifier(),
                defaults: UserDefaults.standard,
                disconnect: {}
            )
        )
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
                ChatView(
                    model: chat,
                    activity: activity,
                    composer: composer,
                    attachments: attachments,
                    voice: voice,
                    states: states,
                    onAttach: { composer.openAttachSheet() }
                )
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
                    .task {
                        // Settings → Gateway is the way out: one closure, set once the shell exists.
                        settings.disconnectGateway = { disconnect() }
                    }
                    .environment(\.readingSize, ReadingScale.size(for: settings.readingSize))
            .onChange(of: holder.connectionStatus) { _, status in
                        states.update(connection: status, wasConnected: holder.everConnected)
                    }
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            ConnectionDotView(dot: states.dot)
                                .padding(.trailing, 4)
                        }
                        #if DEBUG
                        ToolbarItem(placement: .topBarTrailing) {
                            Button {
                                debugOpen = true
                            } label: {
                                Image(systemName: "ladybug")
                            }
                            .accessibilityIdentifier("chat.debug")
                        }
                        #endif
                    }
                    .sheet(isPresented: $debugOpen) {
                        #if DEBUG
                        DebugMenu(states: states, composer: composer)
                        #else
                        EmptyView()
                        #endif
                    }
                    .sheet(isPresented: $settingsOpen) {
                        SettingsView(model: settings, holder: holder)
                    }
                    // The composer's sheets are one value, so a sheet is either open or not.
                    .sheet(isPresented: Binding(
                        get: { composer.sheet != nil },
                        set: { presented in if !presented { composer.closeSheet() } }
                    )) {
                        ComposerSheets(
                            model: composer,
                            attachments: attachments,
                            loadPresets: loadPresets,
                            onPickPhotos: { photosPresented = true },
                            onPickFiles: { filesPresented = true }
                        )
                        .presentationDetents([.medium, .large])
                        .presentationDragIndicator(.visible)
                    }
                    .attachmentPickers(model: attachments, photos: $photosPresented, files: $filesPresented)
                    .overlay {
                        if attachments.lightbox != nil {
                            LightboxView(model: attachments)
                        }
                    }

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
                        onSettings: {
                            closeDrawer()
                            settingsOpen = true
                        }
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
            let scripted = ScriptedChat { state in
                let thread = state.withMarkdown()
                chat.update(thread: thread)
                activity.update(thread: thread)
                composer.update(streaming: thread.turns.contains { $0.running })
                states.update(notices: thread.notices, subagents: thread.subagents, nowMs: Self.nowMs())
            }
            chatDriver = nil
            chat.attach(scripted)
            // The scripted chat answers the composer's commands too, so a tap proves the
            // button rule and the queue without a gateway (I8's debug menu does the same).
            composer.attach(ScriptedComposer(chat: scripted))
            attachments.use(ScriptedUploader(), clear: true)
            voice.use(ScriptedVoice(), recording: ScriptedRecorder())
            await scripted.open()
            return
        }
        #endif
        guard let handle = holder.chat(id: selected?.chatId) else { return }
        let live = CoreChat(holder: holder, handle: handle, onChange: { state in
            chat.update(thread: state)
            activity.update(thread: state)
            composer.update(streaming: state.turns.contains { $0.running })
            states.update(notices: state.notices, subagents: state.subagents, nowMs: Self.nowMs())
        }, onComposer: { snapshot in
            composer.update(snapshot: snapshot)
        })
        live.start()
        chatDriver = live
        driver = live
        chat.attach(driver)
        composer.attach(live)
        attachments.use(live, clear: false)
        voice.use(live)
        await driver.open()
    }

    /// The model sheet's list is the gateway's (`/api/settings` → `model_presets`), read when
    /// the sheet opens rather than at connect.
    private func loadPresets() async -> [ChatModelPreset] {
        await holder.refreshSettings()
        return holder.presets
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
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
