import Foundation

/// What the composer row shows (I5), as Swift's own values. The adapter maps the core's
/// `ComposerState` onto this, so no screen or view model holds a framework type.
struct ComposerSnapshot: Equatable {
    var modelName: String?
    var modelPreset: String?
    var contextWindowTokens: Int?
    /// `turn_end.usage.context_tokens`: the ring's numerator (D15.4).
    var usedTokens: Int?
    var access: WorkspaceAccess = .default
    /// The prompt waiting for the running turn to end, if there is one.
    var queued: String?

    init(
        modelName: String? = nil,
        modelPreset: String? = nil,
        contextWindowTokens: Int? = nil,
        usedTokens: Int? = nil,
        access: WorkspaceAccess = .default,
        queued: String? = nil
    ) {
        self.modelName = modelName
        self.modelPreset = modelPreset
        self.contextWindowTokens = contextWindowTokens
        self.usedTokens = usedTokens
        self.access = access
        self.queued = queued
    }
}

/// `set_workspace_scope` takes exactly these two words (C6.3).
enum WorkspaceAccess: Equatable {
    case `default`
    case full
}

/// Whether the model sheet's list has arrived, failed, or is still coming.
enum PresetState: Equatable { case loading, loaded, failed }

/// One `/model` preset, as the sheet lists it (C6.2). The gateway marks which one is active,
/// so the checkmark is the gateway's answer rather than a comparison made here.
struct ChatModelPreset: Identifiable, Equatable {
    let name: String
    let model: String
    let provider: String?
    let contextWindowTokens: Int?
    let active: Bool
    let isDefault: Bool

    var id: String { name }

    /// The second line of the sheet's row: what the preset actually runs.
    var subtitle: String {
        var parts = [isDefault ? "preset default" : "preset \(name)"]
        if let contextWindowTokens { parts.append("\(contextWindowTokens / 1000)k context") }
        if let provider { parts.append(provider) }
        return parts.joined(separator: " · ")
    }
}

enum PaletteMode: Equatable {
    case slash
    case mention
}

/// One row of the slash or mention sheet, with the text it puts in the field.
struct PaletteRow: Identifiable, Equatable {
    let id: String
    let label: String
    let subtitle: String
    let group: String
    let insert: String
}

struct PaletteSheet: Equatable {
    let mode: PaletteMode
    let title: String
    let rows: [PaletteRow]

    /// Rows keep their group order; the sheet draws a heading whenever it changes.
    var grouped: [(String, [PaletteRow])] {
        var order: [String] = []
        var grouped: [String: [PaletteRow]] = [:]
        for row in rows {
            if grouped[row.group] == nil { order.append(row.group) }
            grouped[row.group, default: []].append(row)
        }
        return order.map { ($0, grouped[$0] ?? []) }
    }
}

/// The composer's sheets, one value so the view can be a `switch` and nothing else.
enum ComposerSheet: Equatable {
    case model
    case access
    case usage
    case palette
    case attach
}

/// What the composer needs from one chat (plan.md §7: one command each way). The adapter's
/// `CoreChat` implements it over a `ChatHandle`; tests and the debug menu answer directly.
@MainActor
protocol ComposerDriving: AnyObject {
    /// Send a prompt on an idle chat, with the uploads the composer is showing (P13). False when
    /// the gateway did not take it.
    func sendPrompt(_ text: String, media: [SentAttachment]) async -> Bool
    /// Queue what was typed while a turn runs (C5.5).
    func enqueue(_ text: String)
    func stop() async
    /// Send the queued prompt now: a steer on the running turn.
    func sendQueuedNow() async
    func discardQueued()
    func setModelPreset(_ preset: String) async
    func setWorkspaceScope(_ access: WorkspaceAccess) async
    /// The slash or mention sheet for this text, or nil when neither is open (C6.4).
    func loadPalette(text: String, cursor: Int) async -> PaletteSheet?
}

/// The composer a screen holds before a gateway is open: it answers nothing.
@MainActor
final class NoopComposer: ComposerDriving {
    func sendPrompt(_ text: String, media: [SentAttachment]) async -> Bool { false }
    func enqueue(_ text: String) {}
    func stop() async {}
    func sendQueuedNow() async {}
    func discardQueued() {}
    func setModelPreset(_ preset: String) async {}
    func setWorkspaceScope(_ access: WorkspaceAccess) async {}
    func loadPalette(text: String, cursor: Int) async -> PaletteSheet? { nil }
}
