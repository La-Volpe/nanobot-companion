import Foundation

/// The composer's state and the rules that decide what its one button does.
///
/// The button rule is the WebUI's, transcribed rather than invented
/// (`showStopButton = isStreaming && !canSend`): while a turn runs an empty field is a Stop
/// button, and typing turns it back into Send, which queues the prompt instead of stopping
/// the turn. Everything else here is a sheet, a chip or a confirmation.
@MainActor
final class ComposerViewModel: ObservableObject {

    @Published var draft: String = ""
    @Published private(set) var snapshot = ComposerSnapshot()
    @Published private(set) var presets: [ChatModelPreset] = []

    /// Whether the sheet's list has arrived: an empty sheet and a read in flight are two screens.
    @Published private(set) var presetState: PresetState = .loading
    @Published private(set) var sheet: ComposerSheet?
    @Published private(set) var palette: PaletteSheet?
    @Published private(set) var streaming = false
    @Published private(set) var lastRefusal: String?
    @Published private(set) var copiedBlockId: String?

    private var driver: ComposerDriving
    /// The cards the composer is showing. They belong to the composer, not to the view: the send
    /// has to carry them and only a message the gateway took may spend them.
    private var attachments: AttachmentViewModel?
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

    /// The upload cards, once the shell has built them.
    func attach(attachments: AttachmentViewModel) {
        self.attachments = attachments
    }

    var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// What the one button is: the artifact's three composer states (`composerState`). A
    /// recording outranks everything, then the stop rule, then text, then the microphone.
    enum PrimaryState: Equatable {
        case voice
        case send
        case stop
    }

    /// A *locked* recording has no finger on it any more, so the same button finishes it and
    /// reads Send.
    func primaryState(recording: Bool, locked: Bool = false) -> PrimaryState {
        if recording, locked { return .send }
        if recording { return .stop }
        if showsStop { return .stop }
        return canSend ? .send : .voice
    }

    /// The WebUI rule (design README, "Controls worth trying").
    var showsStop: Bool { streaming && !canSend }

    /// The ring's percent, as the core resolved it: the WebUI's rounding over the WebUI's choice
    /// of window. Nil before there is a window, because a percentage of nothing is a lie.
    var usagePercent: Int? { snapshot.contextPercent }

    /// The send is waiting for an upload that has not landed. Derived rather than remembered, so
    /// the line goes away the moment the upload does.
    var waitingForUpload: Bool { attachments?.isUploading ?? false }

    /// The line under the field about the send, if there is one: the upload it is waiting for, or
    /// why the gateway refused the last message.
    var refusalLine: String? {
        if waitingForUpload { return "Waiting for the upload to finish." }
        return lastRefusal
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
        // An upload that is still running has no reference, so it is not on this message yet:
        // sending would leave the picture behind and then clear the card that promised it (the
        // dropped-attachment defect, in another costume). Wait instead of sending half a message.
        if waitingForUpload { return }
        guard canSend else { return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if streaming {
            driver.enqueue(text)
            // The chip shows it, so the field is empty again, and the core keeps the text.
            draft = ""
            snapshot.queued = [snapshot.queued, text].compactMap { $0 }.joined(separator: "\n")
            return
        }
        draft = ""
        lastRefusal = nil
        let accepted = await driver.sendPrompt(text, media: attachments?.uploads ?? [])
        if !accepted {
            // A refused send gives the text back rather than losing it, and its uploads
            // stay on screen to go with the next try.
            draft = text
            lastRefusal = "The gateway did not take that message."
        } else {
            // A message the gateway took carries the uploads, so the cards go with it.
            attachments?.clear()
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

    /// The sheet's list comes from the settings read the gateway already answers, not
    /// from a list this app invents. A read that refuses answers nil, which is a different screen
    /// from a gateway with no presets: the sheet says it could not read them and offers the read
    /// again rather than showing an empty list.
    func loadPresets(from load: @escaping () async -> [ChatModelPreset]?) async {
        // A list already read stays on screen: reopening the sheet must not flash a skeleton.
        if presets.isEmpty { presetState = .loading }
        guard let loaded = await load() else {
            presetState = .failed
            return
        }
        presets = loaded
        presetState = .loaded
    }

    /// A pick either applies or the reader is told it did not: the chip already shows the
    /// choice, so a silence would leave it naming a model the chat is not on.
    func pick(preset: ChatModelPreset) async {
        sheet = nil
        lastRefusal = nil
        let sent = await driver.setModelPreset(preset.name)
        if !sent {
            lastRefusal = "The gateway did not take that model."
        }
    }

    func pick(access: WorkspaceAccess) async {
        sheet = nil
        // The chip shows the choice at once and goes back if the gateway refuses it.
        snapshot.access = access
        await driver.setWorkspaceScope(access)
    }

    // MARK: - palettes

    /// Ask for the sheet the current text opens, if any. The core decides; this only
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
