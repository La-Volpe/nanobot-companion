import Foundation

/// The composer's state and the rules that decide what its one button does (plan.md I5).
///
/// The button rule is the WebUI's, transcribed rather than invented
/// (`showStopButton = isStreaming && !canSend`): while a turn runs an empty field is a Stop
/// button, and typing turns it back into Send — which queues the prompt instead of stopping
/// the turn. Everything else here is a sheet, a chip or a confirmation.
@MainActor
final class ComposerViewModel: ObservableObject {

    @Published var draft: String = ""
    @Published private(set) var snapshot = ComposerSnapshot()
    @Published private(set) var presets: [ChatModelPreset] = []
    @Published private(set) var sheet: ComposerSheet?
    @Published private(set) var palette: PaletteSheet?
    @Published private(set) var streaming = false
    @Published private(set) var lastRefusal: String?
    @Published private(set) var copiedBlockId: String?

    private var driver: ComposerDriving
    /// Injected so a test can hold the confirmation open instead of sleeping through it.
    private let sleep: @Sendable (TimeInterval) async -> Void

    /// The design system's copy confirmation window.
    static let copyConfirmation: TimeInterval = 1.5

    init(
        driver: ComposerDriving,
        sleep: @escaping @Sendable (TimeInterval) async -> Void = { seconds in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
    ) {
        self.driver = driver
        self.sleep = sleep
    }

    /// The screen swaps the driver once a gateway is open and the chat has an id.
    func attach(_ driver: ComposerDriving) {
        self.driver = driver
    }

    var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// What the one button is: the artifact's three composer states (`composerState`). A
    /// recording outranks everything, then the stop rule, then text, then the microphone.
    enum PrimaryState: Equatable {
        case voice
        case send
        case stop
    }

    func primaryState(recording: Bool) -> PrimaryState {
        if recording { return .stop }
        if showsStop { return .stop }
        return canSend ? .send : .voice
    }

    /// The WebUI rule (design README, "Controls worth trying").
    var showsStop: Bool { streaming && !canSend }

    /// The ring, rounded to the whole percent the pill prints; nil before there is a window.
    var usagePercent: Int? {
        guard let window = snapshot.contextWindowTokens, window > 0, let used = snapshot.usedTokens else { return nil }
        let percent = (Double(used) / Double(window) * 100).rounded()
        return min(100, max(0, Int(percent)))
    }

    var activePreset: ChatModelPreset? { presets.first(where: \.active) }

    /// The model pill's label: the preset's name when there is one, else the model itself.
    var modelLabel: String {
        if let preset = snapshot.modelPreset, !preset.isEmpty { return preset }
        if let name = snapshot.modelName, !name.isEmpty { return name }
        return "Model"
    }

    // MARK: - input from the chat and the adapter

    func update(streaming: Bool) {
        self.streaming = streaming
    }

    func update(snapshot: ComposerSnapshot) {
        self.snapshot = snapshot
    }

    func update(presets: [ChatModelPreset]) {
        self.presets = presets
    }

    // MARK: - the primary action

    /// What the one button does: stop while a turn runs and the field is empty, queue while a
    /// turn runs and something is typed, send otherwise.
    func primaryAction() async {
        if showsStop {
            await driver.stop()
            return
        }
        guard canSend else { return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if streaming {
            driver.enqueue(text)
            // The chip shows it, so the field is empty again — the core keeps the text.
            draft = ""
            snapshot.queued = [snapshot.queued, text].compactMap { $0 }.joined(separator: "\n")
            return
        }
        draft = ""
        lastRefusal = nil
        let accepted = await driver.sendPrompt(text)
        if !accepted {
            // A refused send gives the text back rather than losing it (C6.1).
            draft = text
            lastRefusal = "The gateway did not take that message."
        }
    }

    // MARK: - the queued prompt chip

    func sendQueuedNow() async {
        await driver.sendQueuedNow()
        snapshot.queued = nil
    }

    func deleteQueued() {
        driver.discardQueued()
        snapshot.queued = nil
    }

    // MARK: - sheets

    func openModelSheet() { sheet = .model }
    func openAccessSheet() { sheet = .access }
    func openUsageSheet() { sheet = .usage }
    func openAttachSheet() { sheet = .attach }
    func closeSheet() { sheet = nil }

    /// The sheet's list comes from the settings read the gateway already answers (C6.2), not
    /// from a list this app invents.
    func loadPresets(from load: @escaping () async -> [ChatModelPreset]) async {
        let loaded = await load()
        if !loaded.isEmpty { presets = loaded }
    }

    func pick(preset: ChatModelPreset) async {
        sheet = nil
        await driver.setModelPreset(preset.name)
    }

    func pick(access: WorkspaceAccess) async {
        sheet = nil
        // The chip shows the choice at once and goes back if the gateway refuses it (C6.3).
        snapshot.access = access
        await driver.setWorkspaceScope(access)
    }

    // MARK: - palettes

    /// Ask for the sheet the current text opens, if any (C6.4). The core decides; this only
    /// carries the answer to the view.
    func refreshPalette() async {
        let loaded = await driver.loadPalette(text: draft, cursor: draft.count)
        palette = loaded
        sheet = loaded == nil ? (sheet == .palette ? nil : sheet) : .palette
    }

    func pick(row: PaletteRow) {
        draft = row.insert
        palette = nil
        sheet = nil
    }

    // MARK: - copy

    func isCopied(_ blockId: String) -> Bool { copiedBlockId == blockId }

    /// A code block's Copy confirms for 1.5 s and then returns to its own label, which is what
    /// the design system specifies.
    func copy(blockId: String) {
        copiedBlockId = blockId
        let sleep = self.sleep
        Task { [weak self] in
            await sleep(ComposerViewModel.copyConfirmation)
            guard let self, self.copiedBlockId == blockId else { return }
            self.copiedBlockId = nil
        }
    }
}
