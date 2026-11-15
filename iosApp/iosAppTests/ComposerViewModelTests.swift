import Foundation
import Testing
@testable import iosApp

/// The composer's rules (plan.md I5): which button the primary action is, what a queued prompt
/// does, the copy confirmation, the usage ring, the model sheet, the access chip and the two
/// palettes. Every branch the view would otherwise hold lives here.
@MainActor
struct ComposerViewModelTests {

    final class FakeDriver: ComposerDriving {
        var sent: [String] = []
        var enqueued: [String] = []
        var stops = 0
        var sendNowCount = 0
        var discarded = 0
        var presets: [String] = []
        var accesses: [WorkspaceAccess] = []
        var palettes = 0
        var paletteRows: PaletteSheet?
        var sendNowSucceeds = true

        func sendPrompt(_ text: String, media: [SentAttachment]) async -> Bool {
            sent.append(text)
            return true
        }

        func enqueue(_ text: String) { enqueued.append(text) }
        func stop() async { stops += 1 }
        func sendQueuedNow() async { sendNowCount += 1; sendNowSucceeds.toggle() }
        func discardQueued() { discarded += 1 }
        func setModelPreset(_ preset: String) async { presets.append(preset) }
        func setWorkspaceScope(_ access: WorkspaceAccess) async { accesses.append(access) }
        func loadPalette(text: String, cursor: Int) async -> PaletteSheet? {
            palettes += 1
            return paletteRows
        }
    }

    /// A sleep the test owns: it records the duration and waits until the test lets it finish,
    /// so "confirms for 1.5 s" is asserted instead of slept through.
    final class Gate {
        private(set) var durations: [TimeInterval] = []
        private var waiters: [CheckedContinuation<Void, Never>] = []

        func sleep(_ seconds: TimeInterval) async {
            durations.append(seconds)
            await withCheckedContinuation { waiters.append($0) }
        }

        func finish() {
            let pending = waiters
            waiters = []
            pending.forEach { $0.resume() }
        }
    }

    private func model(driver: FakeDriver? = nil, gate: Gate? = nil) -> ComposerViewModel {
        ComposerViewModel(driver: driver ?? FakeDriver(), sleep: (gate ?? Gate()).sleep)
    }

    private func preset(_ name: String, active: Bool = false) -> ChatModelPreset {
        ChatModelPreset(name: name, model: "model-\(name)", provider: "ollama", contextWindowTokens: 32_768, active: active, isDefault: name == "default")
    }

    /// The plan's first red line, and the WebUI's own rule: `showStopButton = isStreaming && !canSend`.
    @Test func stopVsSendRule() {
        let model = model()

        model.update(streaming: true)
        #expect(model.showsStop, "a running turn with an empty field is a Stop button")

        model.draft = "one more thing"
        #expect(model.showsStop == false, "typing turns the button back into Send")
        #expect(model.canSend)

        model.draft = "   "
        #expect(model.canSend == false, "whitespace is not a message")
        #expect(model.showsStop, "and an empty field during a turn is Stop again")

        model.update(streaming: false)
        #expect(model.showsStop == false)
    }

    @Test func typingDuringATurnQueuesInsteadOfStopping() async {
        let driver = FakeDriver()
        let model = model(driver: driver)
        model.update(streaming: true)

        model.draft = "  check the tests too "
        await model.primaryAction()

        #expect(driver.enqueued == ["check the tests too"], "Send during a turn queues the prompt")
        #expect(driver.stops == 0, "it does not stop the turn")
        #expect(model.draft.isEmpty, "the field is handed to the chip")

        model.update(streaming: false)
        model.draft = "next question"
        await model.primaryAction()
        #expect(driver.sent == ["next question"])
    }

    /// The design's three composer states: a microphone while the field is empty, Send once
    /// there is text, Stop while a turn runs or a recording is in flight (`composerState`).
    @Test func thePrimaryButtonIsAMicrophoneUntilThereIsText() {
        let model = model()
        #expect(model.primaryState(recording: false) == .voice)

        model.draft = "hello"
        #expect(model.primaryState(recording: false) == .send)

        model.update(streaming: true)
        model.draft = ""
        #expect(model.primaryState(recording: false) == .stop, "a running turn with an empty field")

        model.draft = "queued text"
        #expect(model.primaryState(recording: false) == .send, "typing during a turn offers Send (which queues)")

        #expect(model.primaryState(recording: true) == .stop, "a recording in flight is stopped by the same button")
    }

    @Test func anEmptySendDoesNothing() async {
        let driver = FakeDriver()
        let model = model(driver: driver)

        await model.primaryAction()

        #expect(driver.sent.isEmpty)
        #expect(driver.enqueued.isEmpty)
        #expect(driver.stops == 0)
    }

    @Test func copyConfirmsFor1_5s() async {
        let gate = Gate()
        let model = model(gate: gate)

        model.copy(blockId: "code-1")
        #expect(model.isCopied("code-1"), "the checkmark appears at once")
        #expect(model.isCopied("code-2") == false, "only the block that was copied")
        await waitUntil { !gate.durations.isEmpty }
        #expect(gate.durations == [1.5], "the design system's confirmation window")

        gate.finish()
        await waitUntil { !model.isCopied("code-1") }
        #expect(model.isCopied("code-1") == false, "and the checkmark goes away on its own")
    }

    @Test func theRingReadsUsedTokensOverTheContextWindow() {
        let model = model()

        model.update(snapshot: ComposerSnapshot(contextWindowTokens: 200_000, usedTokens: 84_000))
        #expect(model.usagePercent == 42, "42% of the window, which is the number the pill shows")

        model.update(snapshot: ComposerSnapshot(contextWindowTokens: 200_000, usedTokens: 999_999))
        #expect(model.usagePercent == 100, "a ring never overflows")

        model.update(snapshot: ComposerSnapshot())
        #expect(model.usagePercent == nil, "no window yet, no ring: a percentage of nothing is a lie")
    }

    @Test func theModelSheetOffersTheGatewaysPresetsAndNamesTheActiveOne() {
        let model = model()

        model.update(presets: [preset("default"), preset("primary", active: true)])
        model.openModelSheet()

        #expect(model.sheet == .model)
        #expect(model.presets.map(\.name) == ["default", "primary"])
        #expect(model.presets.first(where: { $0.active })?.name == "primary")
    }

    @Test func theModelSheetSaysWhetherItsListIsStillComing() async {
        let model = model()
        model.openModelSheet()
        #expect(model.presetState == .loading, "the sheet opens before the read answers")

        let first = preset("default")
        await model.loadPresets { [first] }

        #expect(model.presetState == .loaded)
        #expect(model.presets.map(\.name) == ["default"])
    }

    @Test func aRefusedPresetReadSaysSoInsteadOfAnEmptySheet() async {
        let model = model()
        model.openModelSheet()

        await model.loadPresets { nil }

        #expect(model.presetState == .failed, "an empty sheet and a failed read are not the same screen")
        #expect(model.presets.isEmpty)
    }

    @Test func theModelChipNamesThePresetAndOnlyThenTheBareWord() {
        let model = model()
        #expect(model.modelLabel == "Model", "nothing read yet, so the chip says the bare word")

        model.update(snapshot: ComposerSnapshot(modelName: "deepseek-v4.1-flash:cloud"))
        #expect(model.modelLabel == "deepseek-v4.1-flash:cloud", "the gateway's model when no preset is known")

        model.update(snapshot: ComposerSnapshot(modelName: "x", modelPreset: "primary"))
        #expect(model.modelLabel == "primary", "the preset's name outranks the model it runs")
    }

    @Test func pickingAPresetSendsTheModelCommand() async {
        let driver = FakeDriver()
        let model = model(driver: driver)
        model.update(presets: [preset("default"), preset("primary")])
        model.openModelSheet()

        await model.pick(preset: preset("primary"))

        #expect(driver.presets == ["primary"], "the switch is the /model command on this chat (C6.2)")
        #expect(model.sheet == nil, "picking closes the sheet")
    }

    @Test func theAccessChipSwitchesToFullAndBack() async {
        let driver = FakeDriver()
        let model = model(driver: driver)
        model.openAccessSheet()

        await model.pick(access: .full)
        #expect(driver.accesses == [.full])
        #expect(model.snapshot.access == .full, "the chip says Full until the gateway refuses it (C6.3)")

        model.openAccessSheet()
        await model.pick(access: .default)
        #expect(driver.accesses == [.full, .default])
        #expect(model.snapshot.access == .default)
    }

    @Test func slashOpensTheCommandsSheetAndAPickInsertsIt() async {
        let driver = FakeDriver()
        driver.paletteRows = PaletteSheet(
            mode: .slash,
            title: "Commands",
            rows: [
                PaletteRow(id: "c1", label: "/stop", subtitle: "Stop the running turn", group: "Commands", insert: "/stop "),
            ]
        )
        let model = model(driver: driver)

        model.draft = "/sto"
        await model.refreshPalette()
        #expect(driver.palettes == 1)
        #expect(model.sheet == .palette)

        model.pick(row: try! #require(driver.paletteRows?.rows.first))
        #expect(model.draft == "/stop ", "the row's own token is what lands in the field")
        #expect(model.sheet == nil)
    }

    @Test func deletingTheQueuedPromptClearsTheChip() {
        let driver = FakeDriver()
        let model = model(driver: driver)
        model.update(snapshot: ComposerSnapshot(queued: "waiting to send"))

        model.deleteQueued()

        #expect(driver.discarded == 1)
        #expect(model.snapshot.queued == nil)
    }

    @Test func sendNowTakesTheQueuedPromptOffTheChip() async {
        let driver = FakeDriver()
        let model = model(driver: driver)
        model.update(snapshot: ComposerSnapshot(queued: "waiting to send"))

        await model.sendQueuedNow()

        #expect(driver.sendNowCount == 1)
        #expect(model.snapshot.queued == nil)
    }
}

/// Polls a condition across yields: the model's confirmation clears on a task the test released,
/// and a fixed sleep would either be flaky or slow.
@MainActor
func waitUntil(_ condition: () -> Bool, attempts: Int = 200) async {
    for _ in 0..<attempts {
        if condition() { return }
        await Task.yield()
    }
}
