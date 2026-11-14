import Foundation
import SwiftUI
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
    @Published private(set) var connection: ConnectionState = ConnectionStateIdle.shared

    /// The drawer's rows (I2). A plain Swift value, not a framework type: the screens read this.
    @Published private(set) var sessions: [SessionSummary] = []
    @Published private(set) var sessionsLoaded = false

    private var companion: Companion?
    private var subscriptions: [Cancellable] = []

    var isReachable: Bool { companion != nil }

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
        let list = ObservableState<SessionListState>(flow: companion.core.sessions)
        subscriptions.append(
            list.subscribe { [weak self] state in
                MainActor.assumeIsolated {
                    self?.sessions = state.rows.map(SessionSummary.init(row:))
                    self?.sessionsLoaded = state.loaded
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

    func close() {
        subscriptions.forEach { $0.cancel() }
        subscriptions.removeAll()
        companion?.close()
        companion = nil
        connection = ConnectionStateIdle.shared
        sessions = []
        sessionsLoaded = false
    }

    #if DEBUG
    /// The drawer's rows for a scripted launch, so a tap can prove the search states without a
    /// gateway (the debug menu G2 asks for grows from here).
    func seedSessionsForUITest(_ rows: [SessionSummary]) {
        sessions = rows
        sessionsLoaded = true
    }
    #endif

    /// A chat handle for the thread (I3). Nil before a gateway is open.
    func chat(id: String?) -> ChatHandle? {
        companion?.core.chat(chatId: id)
    }

    func refreshSessions() async throws {
        try await companion?.core.refreshSessions()
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
    private var subscription: Cancellable?
    private let holder: CoreHolder

    init(holder: CoreHolder, handle: ChatHandle, onChange: @escaping (ChatThreadState) -> Void) {
        self.holder = holder
        self.handle = handle
        self.onChange = onChange
    }

    func start() {
        let thread = ObservableState<ThreadState>(flow: handle.thread)
        subscription = thread.subscribe { [weak self] state in
            MainActor.assumeIsolated { self?.onChange(ChatThreadState(state: state)) }
        }
    }

    func stopObserving() {
        subscription?.cancel()
        subscription = nil
    }

    func open() async {
        try? await handle.open()
    }

    func send(_ text: String) async -> String? {
        do {
            let receipt = try await handle.send(draft: OutboundDraft(text: text, quote: nil, media: []))
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
    init(state: ThreadState) {
        self.init(
            turns: state.turns.map { turn in
                let segments = turn.answer.segments
                return ChatTurnState(
                    id: turn.id,
                    prompt: turn.prompt?.text,
                    promptPending: turn.prompt?.optimistic ?? false,
                    answer: segments.joined(),
                    // A turn is streaming while it runs and its last segment is still open.
                    answerStreaming: turn.status == TurnStatus.running && segments.last?.closed == false,
                    running: turn.status == TurnStatus.running
                )
            }
        )
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
