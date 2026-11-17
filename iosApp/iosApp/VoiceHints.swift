import Foundation

/// The "Hold to talk" hint is shown once and remembered. The owner's answer to "holding works and
/// tapping does nothing" is a sentence, not a second recording mode; deciding to say it is
/// `VoiceViewModel`'s business, remembering it is the phone's.
protocol VoiceHints: AnyObject {
    func hasSeenHoldHint() -> Bool
    func markHoldHintSeen()
}

/// What a scripted launch and a unit test get: the decisions are real, nothing is on disk, and
/// every run starts with the hint unshown.
final class InMemoryVoiceHints: VoiceHints {
    private var seen = false

    func hasSeenHoldHint() -> Bool { seen }

    func markHoldHintSeen() { seen = true }
}

/// The app's own store: one key beside the settings, because it is the same kind of thing, a
/// decision the reader made by doing something.
final class UserDefaultsVoiceHints: VoiceHints {
    private static let key = "voice.holdHintSeen"
    private let defaults: UserDefaults

    init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    func hasSeenHoldHint() -> Bool { defaults.bool(forKey: Self.key) }

    func markHoldHintSeen() { defaults.set(true, forKey: Self.key) }
}
