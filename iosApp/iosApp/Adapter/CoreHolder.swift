import Foundation
import SwiftUI
import UIKit
import core

/// The adapter layer (plan.md §7): the only files in the app allowed to import the framework.
/// `CoreHolder` owns the `Companion` and translates its typed state flows into Swift state;
/// `CoreGateway` is the connect command; `KeychainSecrets` is the key store.

/// The Keychain, through the core's own `SecureStore` (plan.md D5): one implementation of
/// "secret at rest" for both platforms, not a second one written in Swift.
final class KeychainSecrets: SecretStoring {
    func read() -> String? { CompanionBridgeKt.readGatewaySecret() }

    func store(_ secret: String) { CompanionBridgeKt.storeGatewaySecret(secret: secret) }

    func forget() { CompanionBridgeKt.forgetGatewaySecret() }
}

@MainActor
final class CoreHolder: ObservableObject {

    /// Idle until a gateway is opened, so a view has a connection state before it connects.
    @Published private(set) var connection: ConnectionState = ConnectionStateIdle.shared {
        didSet {
            connectionStatus = ConnectionStatus(connection)
            if connectionStatus == .open { everConnected = true }
        }
    }

    /// The connection as the states screen draws it (I8), and whether it has ever been open —
    /// a socket that was up and dropped is "reconnecting", not "disconnected".
    @Published private(set) var connectionStatus: ConnectionStatus = .idle
    @Published private(set) var everConnected = false

    /// The drawer's rows (I2). A plain Swift value, not a framework type: the screens read this.
    @Published private(set) var sessions: [SessionSummary] = []
    @Published private(set) var sessionsLoaded = false
    /// The last `GET /api/sessions` did not answer; the drawer says so instead of "none".
    @Published private(set) var sessionsFailed = false

    /// The `/model` presets the sheet lists (I5), from the settings read the gateway answers.
    @Published private(set) var presets: [ChatModelPreset] = []

    /// What the two palettes are drawn from; the core maps it, this only keeps the last read.
    private(set) var paletteCatalog = CoreHolder.emptyPalette

    /// The last settings read, kept so a preset list survives the screen that asked for it.
    private var settings = CoreHolder.emptySettings {
        didSet { presets = settings.modelPresets.map(ChatModelPreset.init(preset:)) }
    }

    private var companion: Companion?

    #if DEBUG
    /// A scripted launch has no gateway to read, so the settings answer is seeded: a UI test walks
    /// the screen a connected gateway would fill, not the one an unreachable one leaves behind.
    private var scriptedSettingsSnapshot: SettingsViewModel.Snapshot?
    func seedSettingsForUITest(_ snapshot: SettingsViewModel.Snapshot) {
        scriptedSettingsSnapshot = snapshot
    }
    #endif
    private var subscriptions: [Cancellable] = []

    /// The platform half of a task notice (the rule is the core's). Nil in a scripted launch:
    /// there is no gateway, so no frame, so nothing to announce.
    var notices: TaskNoticeCenter?

    /// Whether the app is in front of the user, which is half of the notice rule.
    private(set) var foreground = false

    /// The background execution assertion that lets a banner outlive the screen (see `announce`).
    private var backgroundWindow: UIBackgroundTaskIdentifier = .invalid

    /// A notification's tap, waiting for the shell to open the chat it names. Published so the
    /// shell can react to a tap that arrives while it is already on screen.
    @Published private(set) var requestedChatId: String?

    var isReachable: Bool { companion != nil }

    /// Where the gateway lives. Media is signature-authenticated, not bearer-gated
    /// (`nanobot/webui/media_api.py:207`), so the only thing a signed path lacks is an origin.
    var mediaBase: String { companion?.core.profile.url ?? "" }

    /// The core's own empty values: a Kotlin data class with every field defaulted has no
    /// no-argument initializer in Swift, and this adapter is the only place that has to know.
    static let emptySettings = SettingsState(
        version: nil, environment: nil, modelName: nil, modelPreset: nil, provider: nil,
        contextWindowTokens: nil, transcription: nil, usage: nil, modelPresets: [], loaded: false
    )
    static let emptyPalette = PaletteCatalog(commands: [], sessions: [], apps: [])

    /// Opens a stack for a gateway. Called once onboarding has an address, a key, and an
    /// answer for the unverified-certificate switch.
    func open(baseUrl: String, secret: String, allowUnverifiedCertificate: Bool) {
        close()
        let companion = CompanionBridgeKt.openCompanion(
            baseUrl: baseUrl,
            secret: secret,
            allowUnverifiedCertificate: allowUnverifiedCertificate
        )
        self.companion = companion
        observe(ObservableState<ConnectionState>(flow: companion.core.connection), into: \.connection)
        observe(ObservableState<SettingsState>(flow: companion.core.settings), into: \.settings)
        observe(ObservableState<PaletteCatalog>(flow: companion.core.palette), into: \.paletteCatalog)
        subscriptions.append(
            ObservableStateKt.observeTaskNotices(flow: companion.core.taskNotice) { [weak self] notice in
                MainActor.assumeIsolated { self?.announce(notice) }
            }
        )
        let list = ObservableState<SessionListState>(flow: companion.core.sessions)
        subscriptions.append(
            list.subscribe { [weak self] state in
                MainActor.assumeIsolated {
                    self?.sessions = state.rows.map(SessionSummary.init(row:))
                    self?.sessionsLoaded = state.loaded
                    self?.sessionsFailed = state.failed
                }
            }
        )
    }

    /// Bootstrap, handshake, `ready`. Returns what the core returned so the connect screen can
    /// pick a sentence; the state itself also arrives through `connection`.
    func connect() async throws -> ConnectResult? {
        guard let companion else { return nil }
        return try await companion.core.connect()
    }

    /// A second handshake on the stack that is already open, which is what "Try again" and a
    /// phone coming back to the foreground both need: the core bootstraps a fresh single-use
    /// token on every `connect()`, so this is the reconnect path and not a resume (C3.4).
    /// True when the gateway greeted the new socket.
    func reconnect() async -> Bool {
        guard companion != nil else { return false }
        let result: ConnectResult?
        do { result = try await connect() } catch { return false }
        return result is ConnectResultConnected
    }

    /// The core allowed a banner: post it, or remember that the phone would not (which is when
    /// the permission becomes worth asking for).
    private func announce(_ notice: core.TaskNotice?) {
        guard let notice, let notices else { return }
        Task {
            await notices.deliver(notice)
            // The banner the window was opened for is on the phone; nothing left to hold the app.
            endBackgroundWindow()
        }
    }

    /// iOS suspends an app the moment it leaves the screen, and a suspended app cannot post the
    /// banner its turn just finished: it does not even see the frame. A short background window
    /// keeps the pump alive long enough to post it. The system's own limit applies (~30 s); a
    /// turn longer than that needs push, which is a gateway and signing decision, not this one.
    private func beginBackgroundWindow() {
        guard backgroundWindow == .invalid else { return }
        backgroundWindow = UIApplication.shared.beginBackgroundTask(withName: "task-notice") { [weak self] in
            self?.endBackgroundWindow()
        }
    }

    private func endBackgroundWindow() {
        guard backgroundWindow != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundWindow)
        backgroundWindow = .invalid
    }

    /// Back to the front of the phone, or away from it: the notice rule needs the difference.
    func setForeground(_ foreground: Bool) {
        self.foreground = foreground
        companion?.core.setForeground(foreground: foreground)
        guard let notices else { return }
        guard foreground else {
            // Away: hold the pump open long enough for a settle frame to become a banner.
            beginBackgroundWindow()
            return
        }
        endBackgroundWindow()
        // A banner that went missing while the app was away is the one moment the dialog is
        // justified: the user has just come back to what the app could not tell them (D8).
        Task { await notices.askIfJustified() }
    }

    /// The chat the shell is showing: the one chat a banner must not announce. `nil` is a chat
    /// whose id the gateway has not minted yet, which the core resolves itself.
    func markOnScreen(chatId: String?) {
        guard let core = companion?.core else { return }
        core.markOnScreen(handle: core.chat(chatId: chatId))
    }

    /// The chat a banner's tap named, consumed once by the shell.
    func requestChatFromNotification(_ chatId: String) {
        requestedChatId = chatId
    }

    func clearRequestedChat() {
        requestedChatId = nil
    }

    /// The permission as iOS reports it, for the Settings row.
    func notificationPermission() async -> AlertsPermission {
        guard let notices else { return .notAsked }
        return await notices.permission()
    }

    func close() {
        subscriptions.forEach { $0.cancel() }
        subscriptions.removeAll()
        companion?.close()
        companion = nil
        connection = ConnectionStateIdle.shared
        connectionStatus = .idle
        everConnected = false
        sessions = []
        sessionsLoaded = false
        sessionsFailed = false
        settings = CoreHolder.emptySettings
        paletteCatalog = CoreHolder.emptyPalette
        requestedChatId = nil
    }

    #if DEBUG
    /// The drawer's rows for a scripted launch, so a tap can prove the search states without a
    /// gateway (the debug menu G2 asks for grows from here).
    func seedSessionsForUITest(_ rows: [SessionSummary]) {
        sessions = rows
        sessionsLoaded = true
        sessionsFailed = false
    }
    #endif

    /// A chat handle for the thread (I3). Nil before a gateway is open.
    func chat(id: String?) -> ChatHandle? {
        companion?.core.chat(chatId: id)
    }

    func refreshSessions() async throws {
        try await companion?.core.refreshSessions()
    }

    /// The automations page's read (I2b): the same socket, one HTTP GET through the core.
    func refreshAutomations() async throws {
        try await companion?.core.refreshAutomations()
    }

    /// The jobs the core last read. A plain array, so the page never touches a framework type.
    var automationJobs: [AutomationJob] {
        guard let flow = companion?.core.automations else { return [] }
        return (flow.value as? AutomationsState)?.jobs ?? []
    }

    /// One automation action. The core refuses a chat change with no values before it sends
    /// anything, and answers with the whole list when the gateway accepts.
    func runAutomation(_ action: AutomationActionKind, id: String) async throws -> AutomationActionResult {
        guard let core = companion?.core else { return AutomationActionResultNotConnected.shared }
        return try await core.runAutomation(id: id, action: action, chatChange: nil)
    }

    /// The settings read as Settings → Overview draws it: the same payload the model sheet's
    /// presets come from, mapped once here.
    var settingsSnapshot: SettingsViewModel.Snapshot {
        #if DEBUG
        if let scriptedSettingsSnapshot { return scriptedSettingsSnapshot }
        #endif
        return SettingsViewModel.Snapshot(
            version: settings.version?.current,
            commit: settings.version?.commit,
            os: settings.environment?.os,
            pythonVersion: settings.environment?.pythonVersion,
            modelName: settings.modelName,
            modelPreset: settings.modelPreset,
            provider: settings.provider,
            contextWindowTokens: settings.contextWindowTokens.map { Int($0.int32Value) },
            transcriptionProvider: settings.transcription?.provider,
            transcriptionModel: settings.transcription?.model,
            presets: settings.modelPresets.map(\.name)
        )
    }

    /// The settings read the model sheet and Settings → Overview both draw from (C6.2, C7.1).
    /// False when it did not answer: both screens say so rather than drawing an empty one.
    @discardableResult
    func refreshSettings() async -> Bool {
        #if DEBUG
        if scriptedSettingsSnapshot != nil { return true }
        #endif
        guard let core = companion?.core else { return false }
        do {
            return try await core.refreshSettings() == nil
        } catch {
            return false
        }
    }

    /// Commands and CLI apps for the palette sheets; the core reads them when a sheet opens
    /// (C6.4), so this is a read per open, not a read per keystroke.
    func refreshPaletteReads() async {
        _ = try? await companion?.core.refreshPalettes()
    }

    /// One subscription, kept so `close()` can end it. The bridge delivers on the main queue,
    /// which is why `assumeIsolated` is safe rather than hopeful.
    private func observe<T: AnyObject>(_ state: ObservableState<T>, into path: ReferenceWritableKeyPath<CoreHolder, T>) {
        let subscription = state.subscribe { [weak self] value in
            MainActor.assumeIsolated { self?[keyPath: path] = value }
        }
        subscriptions.append(subscription)
    }
}

/// One drawer row, as Swift needs it: the title to search and the preview to show, nothing from
/// the framework. Built in the adapter layer so the view model and its tests stay Kotlin-free.
struct SessionSummary: Identifiable, Equatable {
    let key: String
    let chatId: String
    let title: String
    let preview: String
    let updatedAtMs: Int64?

    var id: String { key }

    /// The gateway titles a chat from its own first message; an untitled row still needs a name.
    var displayTitle: String {
        title.isEmpty ? "Untitled session" : title
    }

    init(key: String, chatId: String, title: String, preview: String, updatedAtMs: Int64?) {
        self.key = key
        self.chatId = chatId
        self.title = title
        self.preview = preview
        self.updatedAtMs = updatedAtMs
    }

    init(row: SessionRow) {
        self.init(
            key: row.key,
            chatId: row.chatId,
            title: row.title,
            preview: row.preview,
            updatedAtMs: row.updatedAtMs?.int64Value
        )
    }
}

/// One chat, as the app drives it (I3). Reads the handle's thread flow and maps it to
/// `ChatThreadState`; every command forwards to the facade, and nothing branches here.
@MainActor
final class CoreChat: ChatDriving {
    private let handle: ChatHandle
    private let onChange: (ChatThreadState) -> Void
    private let onComposer: (ComposerSnapshot) -> Void
    private var subscription: Cancellable?
    private var composerSubscription: Cancellable?
    private var historySubscription: Cancellable?
    /// The page read's state, kept here so a thread the socket republishes does not lose it.
    private var history: ThreadHistory = .loading
    private var lastThread = ChatThreadState()
    private let holder: CoreHolder

    init(
        holder: CoreHolder,
        handle: ChatHandle,
        onChange: @escaping (ChatThreadState) -> Void,
        onComposer: @escaping (ComposerSnapshot) -> Void = { _ in }
    ) {
        self.holder = holder
        self.handle = handle
        self.onChange = onChange
        self.onComposer = onComposer
    }

    func start() {
        // Built before the thread's subscription: a thread that arrives first would otherwise
        // draw one frame of skeleton for a page this chat has already read.
        let status = ObservableState<HistoryStatus>(flow: handle.history)
        history = status.value.threadHistory
        let thread = ObservableState<ThreadState>(flow: handle.thread)
        subscription = thread.subscribe { [weak self] state in
            MainActor.assumeIsolated {
                guard let self else { return }
                var next = ChatThreadState(state: state, mediaBase: self.holder.mediaBase)
                next.history = self.history
                self.lastThread = next
                self.onChange(next)
            }
        }
        // The page read is its own flow: the reducer never touches it, so the two arrive apart.
        historySubscription = status.subscribe { [weak self] value in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.history = value.threadHistory
                self.lastThread.history = self.history
                self.onChange(self.lastThread)
            }
        }
        // The composer's model, ring and queue live on the handle, so they arrive the same way.
        let composer = ObservableState<ComposerState>(flow: handle.composer)
        composerSubscription = composer.subscribe { [weak self] state in
            MainActor.assumeIsolated { self?.onComposer(ComposerSnapshot(state: state)) }
        }
    }

    func stopObserving() {
        subscription?.cancel()
        subscription = nil
        composerSubscription?.cancel()
        composerSubscription = nil
        historySubscription?.cancel()
        historySubscription = nil
    }

    func open() async {
        try? await handle.open()
    }

    func send(_ text: String) async -> String? {
        await sendDraft(text: text, media: [])
    }

    /// The one place an outbound draft is built, so a caller that has uploads says so and a
    /// caller that has none says that instead (P13: the composer's cards used to be drawn and
    /// then left out of this draft).
    private func sendDraft(text: String, media: [SentAttachment]) async -> String? {
        let draft = OutboundDraft(
            text: text,
            quote: nil,
            media: media.map { AttachmentRef(reference: $0.reference, name: $0.name) }
        )
        do {
            let receipt = try await handle.send(draft: draft)
            if let accepted = receipt as? SendReceiptAccepted { return accepted.turnId }
            return nil
        } catch {
            return nil
        }
    }

    func stop() async {
        try? await handle.stop()
    }
}

extension ChatThreadState {
    /// `core.ThreadState` → the Swift value the chat screen draws. The core keeps the model,
    /// the streaming flags and the notices; this only renames what a view needs.
    init(state: ThreadState, mediaBase: String = "") {
        self.init(
            turns: state.turns.map { turn in
                let segments = turn.answer.segments
                // A turn is streaming while it runs and its last segment is still open.
                let streaming = turn.status == TurnStatus.running && segments.last?.closed == false
                return ChatTurnState(
                    id: turn.id,
                    prompt: turn.prompt?.text,
                    promptPending: turn.prompt?.optimistic ?? false,
                    answer: segments.joined(),
                    answerStreaming: streaming,
                    running: turn.status == TurnStatus.running,
                    activity: turn.activity.compactMap(ChatActivityItem.init(item:)),
                    startedAtMs: turn.startedAtMs?.int64Value,
                    media: turn.answer.media.map { MediaRow(attachment: $0, mediaBase: mediaBase) },
                    promptMedia: turn.prompt?.media.map { MediaRow(attachment: $0, mediaBase: mediaBase) } ?? [],
                    blocks: Reading.blocks(turn.answer.text, streaming: streaming)
                )
            },
            notices: state.notices.compactMap(ThreadNotice.init(notice:)),
            subagents: state.subagents.map(SubagentRow.init(subagent:))
        )
    }
}

extension MediaRow {
    /// `core.MediaAttachment` → the row a view draws. The core kept the path the gateway signed;
    /// `mediaBase` is the origin that turns it into something `URLSession` can open.
    init(attachment: MediaAttachment, mediaBase: String) {
        self.init(
            id: attachment.id,
            name: attachment.name,
            url: URL(string: attachment.absoluteUrl(baseUrl: mediaBase)),
            kind: MediaRowKind(kind: attachment.kind)
        )
    }
}

extension MediaRowKind {
    /// The Kotlin enum arrives as a class, not a Swift `case`, so it is read by its own name —
    /// the same way `SandboxIssue` is. A kind this build has never heard of is a file card.
    init(kind: MediaKind) {
        switch kind.name {
        case "Image": self = .image
        case "Video": self = .video
        case "Audio": self = .audio
        default: self = .file
        }
    }
}

extension ThreadNotice {
    /// The core's `Notice` is a sealed interface: seven casts, and an unknown one is dropped
    /// rather than drawn as a blank row.
    init?(notice: Notice) {
        if let compaction = notice as? NoticeCompaction {
            self = .compaction(id: compaction.id, phase: compaction.phase, reason: compaction.reason)
        } else if let recovery = notice as? NoticeRecovery {
            self = .recovery(id: recovery.id, status: recovery.status, canContinue: recovery.canContinue, reason: recovery.reason)
        } else if let retry = notice as? NoticeRetry {
            self = .retry(
                id: retry.id,
                attempt: Int(retry.attempt),
                maxAttempts: retry.maxAttempts.map { Int($0.int32Value) },
                errorKind: retry.errorKind,
                retryAtMs: retry.retryAtMs.map { Int($0.int64Value) }
            )
        } else if let goal = notice as? NoticeGoal {
            self = .goal(id: goal.id, active: goal.active, status: goal.status, summary: goal.summary)
        } else if let failure = notice as? NoticeFailure {
            self = .failure(id: failure.id, errorKind: failure.errorKind, message: failure.message)
        } else if let fallback = notice as? NoticePresetFallback {
            self = .presetFallback(id: fallback.id, modelName: fallback.modelName)
        } else if let sandbox = notice as? NoticeSandbox {
            self = .sandbox(id: sandbox.id, issue: sandbox.issue.name)
        } else {
            return nil
        }
    }
}

extension SubagentRow {
    init(subagent: Subagent) {
        self.init(
            id: subagent.id,
            label: subagent.label,
            state: subagent.state,
            iteration: Int(subagent.iteration),
            elapsedSeconds: subagent.elapsedSeconds
        )
    }
}

extension HistoryStatus {
    /// The page read as the thread draws it: a skeleton, an empty chat, or the failure sentence.
    var threadHistory: ThreadHistory {
        switch self {
        case HistoryStatus.loaded: return .loaded
        case HistoryStatus.failed: return .failed
        default: return .loading
        }
    }
}

extension ConnectionStatus {
    /// The core's four states plus its failure, as the dot and banner read them.
    init(_ state: ConnectionState) {
        switch state {
        case let open as ConnectionStateOpen:
            _ = open
            self = .open
        case let connecting as ConnectionStateConnecting:
            _ = connecting
            self = .connecting
        case let offline as ConnectionStateOffline:
            _ = offline
            self = .offline
        case let failed as ConnectionStateFailed:
            self = .failed(ConnectionFailureKind(failure: failed.failure), detail: failed.detail)
        default:
            self = .idle
        }
    }
}

extension ConnectionFailureKind {
    /// The taxonomy, named once more in Swift (D38's lowercased case names).
    init(failure: ConnectionFailure) {
        switch failure {
        case ConnectionFailure.authfailed: self = .authFailed
        case ConnectionFailure.tlsrejected: self = .tlsRejected
        case ConnectionFailure.unreachable: self = .unreachable
        case ConnectionFailure.timeout: self = .timeout
        case ConnectionFailure.protocoltoonew: self = .protocolTooNew
        case ConnectionFailure.missingcapability: self = .missingCapability
        case ConnectionFailure.missingfield: self = .missingField
        default: self = .serverError
        }
    }
}

/// `ActivityItem` is a sealed interface, so the four cases are four casts and nothing else:
/// an item this build does not know is dropped rather than drawn as a blank row.
extension ChatThreadState {
    /// A thread built in Swift — the DEBUG scripted chat — still gets the core's markdown, so a
    /// flow test draws the same blocks a live turn does instead of a wall of raw text.
    func withMarkdown() -> ChatThreadState {
        var copy = self
        copy.turns = turns.map { turn in
            var parsed = turn
            parsed.blocks = Reading.blocks(turn.answer, streaming: turn.answerStreaming)
            return parsed
        }
        return copy
    }
}

extension ChatActivityItem {
    init?(item: ActivityItem) {
        if let reasoning = item as? ActivityItemReasoning {
            self.init(id: item.id, kind: .reasoning(text: reasoning.text, done: reasoning.done))
        } else if let tool = item as? ActivityItemTool {
            self.init(
                id: item.id,
                kind: .tool(
                    name: tool.name,
                    summary: tool.summary,
                    status: ToolRunStatus(tool.status),
                    detail: tool.detail
                )
            )
        } else if let edit = item as? ActivityItemFileEdit {
            self.init(
                id: item.id,
                kind: .fileEdit(
                    FileEditState(
                        path: edit.path,
                        status: ToolRunStatus(edit.status),
                        added: Int(edit.added),
                        deleted: Int(edit.deleted),
                        operation: edit.operation,
                        diff: edit.diff ?? ""
                    )
                )
            )
        } else if let search = item as? ActivityItemSearch {
            self.init(id: item.id, kind: .search(query: search.query, sources: search.sources))
        } else {
            return nil
        }
    }
}

private extension ToolRunStatus {
    /// The core's enum cases arrive as class instances with lowercased Swift names (D38).
    init(_ status: ToolStatus) {
        if status == ToolStatus.failed {
            self = .failed
        } else if status == ToolStatus.running {
            self = .running
        } else {
            self = .done
        }
    }
}

/// One framework detail that Swift should not repeat everywhere: joining the answer's segments.
private extension Array where Element == StreamSegment {
    func joined() -> String {
        map(\.text).joined()
    }
}

/// The connect command as the screen sees it: no Kotlin types in the signature, one outcome.
@MainActor
final class CoreGateway: GatewayConnecting {
    private let holder: CoreHolder

    init(holder: CoreHolder) {
        self.holder = holder
    }

    func connect(address: String, key: String, allowUnverifiedCertificate: Bool) async -> GatewayOutcome {
        holder.open(baseUrl: address, secret: key, allowUnverifiedCertificate: allowUnverifiedCertificate)
        do {
            guard let result = try await holder.connect() else {
                return .failed(.unreachable, detail: "the gateway stack is not open")
            }
            switch result {
            case let connected as ConnectResultConnected:
                return .connected(model: connected.modelName)
            case let failed as ConnectResultFailed:
                return .failed(Self.kind(of: failed.failure), detail: failed.detail)
            default:
                return .failed(.serverError, detail: nil)
            }
        } catch {
            // A thrown call is the platform's failure, not the gateway's: nothing answered.
            return .failed(.unreachable, detail: (error as NSError).localizedDescription)
        }
    }

    func disconnect() {
        holder.close()
    }

    /// The taxonomy is the core's, and this is the one place its eight entries are named in
    /// Swift (D38: the Swift names are the lowercased Kotlin case names).
    private static func kind(of failure: ConnectionFailure) -> ConnectionFailureKind {
        switch failure {
        case ConnectionFailure.authfailed: .authFailed
        case ConnectionFailure.tlsrejected: .tlsRejected
        case ConnectionFailure.unreachable: .unreachable
        case ConnectionFailure.timeout: .timeout
        case ConnectionFailure.protocoltoonew: .protocolTooNew
        case ConnectionFailure.missingcapability: .missingCapability
        case ConnectionFailure.missingfield: .missingField
        default: .serverError
        }
    }
}


// MARK: - the composer, as a chat drives it

extension CoreChat: ComposerDriving {

    func sendPrompt(_ text: String, media: [SentAttachment]) async -> Bool {
        await sendDraft(text: text, media: media) != nil
    }

    func enqueue(_ text: String) {
        handle.enqueue(text: text)
    }

    func sendQueuedNow() async {
        _ = try? await handle.sendQueuedNow()
    }

    func discardQueued() {
        handle.discardQueued()
    }

    func setModelPreset(_ preset: String) async {
        try? await handle.setModelPreset(preset: preset)
    }

    func setWorkspaceScope(_ access: WorkspaceAccess) async {
        try? await handle.setWorkspaceScope(mode: access == .full ? WorkspaceScope.full : WorkspaceScope.default_)
    }

    /// The two palettes are the core's rules applied to the reads (`PaletteQuery`, C6.4). The
    /// read is refreshed when a sheet opens, which is what the WebUI does too.
    func loadPalette(text: String, cursor: Int) async -> PaletteSheet? {
        await holder.refreshPaletteReads()
        guard let state = PaletteQuery.shared.forComposer(
            catalog: holder.paletteCatalog,
            text: text,
            cursor: Int32(cursor)
        ) else { return nil }
        return PaletteSheet(state: state)
    }
}

extension ComposerSnapshot {
    /// `core.ComposerState` → what the row draws. The ring's two numbers come from the thread's
    /// own usage (`turn_end.usage.context_tokens` over `context_window_tokens`, D15.4).
    init(state: ComposerState) {
        self.init(
            modelName: state.modelName,
            modelPreset: state.modelPreset,
            contextWindowTokens: state.contextWindowTokens.map { Int($0.int32Value) },
            usedTokens: state.usage.map { Int($0.contextTokens) },
            access: state.accessMode == WorkspaceScope.full ? .full : .default,
            queued: state.queued
        )
    }
}

extension ChatModelPreset {
    init(preset: ModelPreset) {
        self.init(
            name: preset.name,
            model: preset.model,
            provider: preset.provider,
            contextWindowTokens: preset.contextWindowTokens.map { Int($0.int32Value) },
            active: preset.active,
            isDefault: preset.isDefault
        )
    }
}

extension PaletteSheet {
    /// A `PaletteState` is already the resolved sheet: which mode, which rows, in which order.
    /// Only the copy is the platform's, so the group headings are named here.
    init(state: PaletteState) {
        let rows = state.groups.flatMap { group -> [PaletteRow] in
            let heading = PaletteSheet.groupTitle(group.kind)
            return group.items.map { item in
                PaletteRow(
                    id: "\(heading)-\(item.label)",
                    label: item.label,
                    subtitle: item.subtitle,
                    group: heading,
                    insert: PaletteSheet.insertion(for: item)
                )
            }
        }
        self.init(mode: state.mode == core.PaletteMode.slash ? .slash : .mention, title: PaletteSheet.title(state.mode), rows: rows)
    }

    private static func title(_ mode: core.PaletteMode) -> String {
        mode == core.PaletteMode.slash ? "Commands" : "Mention"
    }

    private static func groupTitle(_ kind: PaletteGroupKind) -> String {
        switch kind {
        case PaletteGroupKind.sessions: "Nanobot conversations"
        case PaletteGroupKind.cliapps: "CLI apps"
        default: "Commands"
        }
    }

    /// A picked row replaces the typed token with its own text plus a space, so typing carries on.
    private static func insertion(for item: PaletteItem) -> String {
        item.label + " "
    }
}

// MARK: - attachments and voice

extension CoreChat: StateDriving {

    func dismissNotice(id: String) {
        handle.dismissNotice(id: id)
    }

    func recovery(action: String) async {
        switch action {
        case "continue": _ = try? await handle.continueRecovery()
        default: _ = try? await handle.dismissRecovery()
        }
    }

    func cancelSubagent(id: String) async {
        _ = try? await handle.cancelSubagent(taskId: id)
    }
}

extension CoreChat: AttachmentDriving {

    /// One picked file: the core validates it against `ready.limits` before a byte leaves the
    /// device and returns the gateway's reference or a typed refusal (C6.6).
    func upload(name: String, mimeType: String, data: Data) async -> AttachmentOutcome {
        let file = PickedFile(name: name, mimeType: mimeType, bytes: data.kotlinByteArray)
        let result = try? await handle.upload(file: file)
        switch result {
        case let uploaded as UploadResultUploaded:
            return .uploaded(reference: uploaded.reference)
        case let rejected as UploadResultRejected:
            return .rejected(AttachmentReject(rejection: rejected.reason))
        default:
            return .failed
        }
    }

    /// The gateway's upload whitelist, asked of the core before a byte is prepared: an iPhone
    /// photo is HEIC by default and HEIC is not on the list, so the picker has to know.
    func accepts(mimeType: String) -> Bool { UploadWhitelist.takes(mimeType) }
}

/// The one place the app asks which upload types the gateway takes. `gatewayAcceptsUpload` is
/// the same set `AttachmentPolicy` refuses with, so the answer cannot drift from the refusal.
enum UploadWhitelist {
    static func takes(_ mimeType: String) -> Bool {
        UploadFormatsKt.gatewayAcceptsUpload(mimeType: mimeType)
    }
}

extension CoreChat: VoiceDriving {

    /// The recording goes out as a data URL and comes back as text on the same gateway turn
    /// (`transcription_ws.py`, C6.5). The core owns both bounds.
    func transcribe(pcm: Data, durationMs: Int) async -> VoiceOutcome {
        let result = try? await handle.transcribe(pcm: pcm.kotlinByteArray, durationMs: Int64(durationMs))
        switch result {
        case let transcribed as TranscriptionResultTranscribed:
            return .transcribed(transcribed.text)
        case let rejected as TranscriptionResultRejected:
            return .refused(rejected.reason.name)
        case let failed as TranscriptionResultFailed:
            return .refused(failed.detail)
        default:
            return .refused("not_connected")
        }
    }
}

extension AttachmentReject {
    /// The seven typed refusals, named once. `copyKey` is the core's; the sentence is ours.
    init(rejection: AttachmentRejection) {
        switch rejection {
        case is AttachmentRejectionEmptyFile: self = .empty
        case is AttachmentRejectionTooManyFiles: self = .tooMany
        case is AttachmentRejectionFileTooLarge: self = .fileTooLarge
        case is AttachmentRejectionTotalTooLarge: self = .totalTooLarge
        case is AttachmentRejectionTooManyVideos: self = .tooManyVideos
        case is AttachmentRejectionVideoTooLarge: self = .videoTooLarge
        default: self = .unsupported
        }
    }
}

extension Data {
    /// Swift `Data` → the Kotlin `ByteArray` the facade takes. One copy per upload, which is
    /// what a frame-sized file costs anyway.
    var kotlinByteArray: KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}

// MARK: - markdown

/// The adapter's one translation of the core's markdown tree into the Swift values the views
/// draw. Everything here is a cast: the parser's decisions are the core's.
enum Reading {

    static func blocks(_ text: String, streaming: Bool = false) -> [ReadingBlock] {
        guard !text.isEmpty else { return [] }
        return mark(MarkdownFacadeKt.markdownDocument(text: text, streaming: streaming).blocks)
    }

    static func mark(_ blocks: [MarkdownBlock]) -> [ReadingBlock] {
        blocks.compactMap { block in
            if let heading = block as? MarkdownBlockHeading {
                return .heading(level: Int(heading.level), inlines: mark(heading.inlines))
            }
            if let paragraph = block as? MarkdownBlockParagraph {
                return .paragraph(inlines: mark(paragraph.inlines))
            }
            if let list = block as? MarkdownBlockListBlock {
                return .list(
                    ordered: list.ordered,
                    start: Int(list.start),
                    items: list.items.map {
                        ReadingListItem(
                            depth: Int($0.depth),
                            inlines: mark($0.inlines),
                            checked: $0.checked?.boolValue
                        )
                    }
                )
            }
            if let fence = block as? MarkdownBlockCodeFence {
                return .code(language: fence.language, code: fence.code, closed: fence.closed)
            }
            if let table = block as? MarkdownBlockTable {
                return .table(
                    header: table.header.map(mark),
                    alignments: table.alignments.map(mark),
                    rows: table.rows.map { row in row.map(mark) }
                )
            }
            if let quote = block as? MarkdownBlockQuote {
                return .quote(mark(quote.blocks))
            }
            if block is MarkdownBlockRule {
                return .rule
            }
            return nil
        }
    }

    static func mark(_ inlines: [Inline]) -> [ReadingInline] {
        inlines.compactMap { inline in
            if let text = inline as? InlineText { return .text(text.text) }
            if let code = inline as? InlineCode { return .code(code.text) }
            if let emphasis = inline as? InlineEmphasis { return .emphasis(mark(emphasis.inlines)) }
            if let strong = inline as? InlineStrong { return .strong(mark(strong.inlines)) }
            if let struck = inline as? InlineStrikethrough { return .strikethrough(mark(struck.inlines)) }
            if let link = inline as? InlineLink { return .link(mark(link.inlines), destination: link.destination) }
            return nil
        }
    }

    static func mark(_ alignment: core.Alignment) -> ReadingAlignment {
        switch alignment {
        case core.Alignment.right: .right
        case core.Alignment.center: .center
        case core.Alignment.left: .left
        default: .none
        }
    }
}

/// The automations page's driver over the real core: the one file that knows what a Kotlin
/// `AutomationJob` is, so the screen and its tests speak `AutomationRow`.
@MainActor
final class CoreAutomations: AutomationsDriving {
    private let holder: CoreHolder

    init(holder: CoreHolder) {
        self.holder = holder
    }

    func load() async -> [AutomationRow]? {
        do {
            try await holder.refreshAutomations()
        } catch {
            // A failed read is not an empty page: the view keeps the rows it had.
            guard !holder.automationJobs.isEmpty else { return nil }
        }
        return CoreAutomations.rows(from: holder.automationJobs)
    }

    func act(_ act: AutomationAct, id: String) async -> String? {
        let action: AutomationActionKind
        switch act {
        case .run: action = .run
        case .pause: action = .disable
        case .resume: action = .enable
        case .delete: action = .delete_
        }
        do {
            let result = try await holder.runAutomation(action, id: id)
            return CoreAutomations.refusal(from: result)
        } catch {
            return "The gateway did not answer."
        }
    }

    /// The gateway's own status and words, turned into one sentence. A protection is not an
    /// error to apologise for: it is the gateway saying this job is its own.
    static func refusal(from result: AutomationActionResult) -> String? {
        if result is AutomationActionResultDone || result is AutomationActionResultAccepted {
            return nil
        }
        if let refused = result as? AutomationActionResultRefused {
            switch refused.status {
            case 403: return "This job belongs to the gateway and cannot be changed from here."
            case 409: return "That job is busy right now."
            case 404: return "That automation is gone."
            default: return refused.message ?? "The gateway refused that."
            }
        }
        if result is AutomationActionResultNotConnected { return "The gateway is not connected." }
        if result is AutomationActionResultTimeout { return "The gateway did not answer in time." }
        return nil
    }

    static func rows(from jobs: [AutomationJob]) -> [AutomationRow] {
        jobs.map { job in
            AutomationRow(
                id: job.id,
                name: job.name,
                schedule: scheduleText(job.schedule),
                detail: detailText(job.state, local: job.schedule.kind == "local"),
                enabled: job.enabled,
                isSystem: job.protected_
            )
        }
    }

    /// The design's second line: a cron expression, an interval, a date, or a local trigger.
    static func scheduleText(_ schedule: AutomationSchedule) -> String {
        switch schedule.kind {
        case "cron": return schedule.expr ?? "Cron"
        case "every":
            guard let every = schedule.everyMs?.int64Value, every > 0 else { return "Repeating" }
            let minutes = every / 60_000
            if minutes % 60 == 0 { return "Every \(minutes / 60) h" }
            return "Every \(minutes) min"
        case "at":
            guard let at = schedule.atMs?.doubleValue else { return "Once" }
            return "Once · \(Self.stamp(at / 1000))"
        case "local": return "Local trigger"
        default: return schedule.kind.isEmpty ? "No schedule" : schedule.kind
        }
    }

    /// The third line: what the job is doing, which is the next run when it is healthy and the
    /// last error when it is not.
    static func detailText(_ state: AutomationRunState, local: Bool) -> String {
        if let error = state.lastError, !error.isEmpty { return error }
        if state.pending { return "Running now" }
        if let next = state.nextRunAtMs?.doubleValue { return "Next · \(stamp(next / 1000))" }
        if local { return "Fires when the gateway calls it" }
        if let last = state.lastStatus { return last.capitalized }
        return "No run yet"
    }

    /// Absolute and short — "Fri 08:00". A relative "in 3 h" would need a clock ticking under the
    /// view, and reads worse on a page you open once.
    private static func stamp(_ seconds: Double) -> String {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.setLocalizedDateFormatFromTemplate("EEE HH:mm")
        return formatter.string(from: Date(timeIntervalSince1970: seconds))
    }
}
