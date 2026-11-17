import Foundation
import SwiftUI

/// Onboarding's three fields, one button and its states (design artifact
/// `data-view="onboarding"`). Every branch worth testing is here rather than in the view:
/// what counts as a usable address, when the key is required, what a failure says, and the one
/// rule the unverified-certificate switch exists for, which is that trusting a certificate is
/// the user's decision and never the app's.
@MainActor
final class ConnectViewModel: ObservableObject {

    /// The button's own state machine: idle → connecting → idle, plus the two terminals.
    enum Phase: Equatable {
        case idle
        case connecting
        case connected(model: String?)
        case failed
    }

    @Published var address: String
    @Published var key: String
    @Published var allowUnverifiedCertificate: Bool

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var message: String?

    /// A TLS refusal is the one failure whose remedy is a control on this screen: the switch
    /// below. The view highlights it instead of retrying the same refusal.
    @Published private(set) var trustSwitchIsTheRemedy = false

    private let gateway: GatewayConnecting
    private let credentials: CredentialsStoring
    /// What this launch read, so a second look can tell an untouched screen from a typed one.
    private let launchAddress: String
    private let launchKey: String
    /// The launch's own values (DEBUG live instrument), kept because a second look resolves the
    /// store again and the instrument's gateway still wins over it.
    private let prefilledAddress: String?
    private let prefilledKey: String?

    /// `prefilledAddress` and `prefilledKey` exist for the live session instrument (DEBUG only):
    /// the gateway arrives through the launch environment instead of being typed, so its secret
    /// never reaches a test log. They win over the store because the instrument names the gateway
    /// it is about, and what it connects with is then stored like any other connect.
    init(
        gateway: GatewayConnecting,
        credentials: CredentialsStoring,
        prefilledAddress: String? = nil,
        prefilledKey: String? = nil
    ) {
        self.gateway = gateway
        self.credentials = credentials
        self.prefilledAddress = prefilledAddress
        self.prefilledKey = prefilledKey
        let address = prefilledAddress ?? credentials.address ?? ""
        // The store's key is the whole point of remembering: a launch that has one reconnects
        // instead of asking the user to type a secret the app already holds.
        let key = prefilledKey ?? credentials.storedKey ?? ""
        self.address = address
        self.key = key
        self.allowUnverifiedCertificate = credentials.allowUnverifiedCertificate
        self.launchAddress = address
        self.launchKey = key
    }

    /// Both halves and nothing of the user's to do: this launch can open a gateway by itself. An
    /// address without a key (or the reverse) is half a setup, which the connect screen finishes.
    var hasStoredGateway: Bool {
        !address.trimmingCharacters(in: .whitespaces).isEmpty && !key.isEmpty
    }

    /// The store, read again: a Keychain read that ran on a phone still locked comes back empty,
    /// and the return to the foreground is the second chance. True when the device holds a gateway
    /// now, which it is then prefilled with. Nothing is adopted over a screen the owner has started
    /// typing on: what someone is halfway through entering is theirs.
    func resolveStoredGateway() -> Bool {
        let storedAddress = (prefilledAddress ?? credentials.address ?? "").trimmingCharacters(in: .whitespaces)
        let storedKey = prefilledKey ?? credentials.storedKey ?? ""
        guard !storedAddress.isEmpty, !storedKey.isEmpty else { return false }
        guard address == launchAddress, key == launchKey else { return false }
        address = storedAddress
        key = storedKey
        return true
    }

    var isConnecting: Bool { phase == .connecting }

    var canConnect: Bool { !isConnecting && hasStoredGateway }

    /// Whether the failure on screen is worth offering "try again" for; the core decides this
    /// per entry in the taxonomy, the screen only reads it.
    var offersRetry: Bool {
        guard let kind = failureKind else { return false }
        return kind.isRetryable
    }

    private(set) var failureKind: ConnectionFailureKind?

    /// The one command. Local refusals come first and cost no round trip (design's "invalid URL"
    /// and "missing key" states).
    func connect() async {
        await attempt(closeOnFailure: true)
    }

    /// The launch's own attempt on the gateway the device already holds: the same bookkeeping the
    /// button does, and one difference: it keeps the stack when the handshake fails. A typed
    /// attempt leaves a form to fix; a launch leaves the shell's banner, and that banner's "Try
    /// again" is a reconnect on the stack this keeps open. True when the gateway greeted it.
    @discardableResult
    func reopenStoredGateway() async -> Bool {
        await attempt(closeOnFailure: false)
    }

    private func attempt(closeOnFailure: Bool) async -> Bool {
        guard !isConnecting else { return false }
        message = nil
        trustSwitchIsTheRemedy = false
        failureKind = nil

        let trimmedAddress = address.trimmingCharacters(in: .whitespaces)
        guard !trimmedAddress.isEmpty else { return refuse(.addressMissing) }
        guard trimmedAddress.contains("://") else { return refuse(.addressNeedsScheme) }
        guard !key.isEmpty else { return refuse(.keyMissing) }

        phase = .connecting
        let outcome = await gateway.connect(
            address: trimmedAddress,
            key: key,
            allowUnverifiedCertificate: allowUnverifiedCertificate
        )

        switch outcome {
        case let .connected(model):
            // The key is only kept once it was accepted; a refused one is not persisted.
            credentials.remember(address: trimmedAddress, key: key, allowUnverifiedCertificate: allowUnverifiedCertificate)
            phase = .connected(model: model)
            message = model.map { "Connected to \($0)" } ?? "Connected"
            return true
        case let .failed(failure, detail):
            failureKind = failure
            trustSwitchIsTheRemedy = failure == .tlsRejected && !allowUnverifiedCertificate
            message = ConnectionCopy.text(for: failure, detail: detail)
            phase = .failed
            if closeOnFailure { gateway.disconnect() }
            return false
        }
    }

    /// The switch is the user's decision about one gateway; turning it on never retries by
    /// itself, because a silent retry after trusting a certificate is the wrong way round.
    func setAllowUnverifiedCertificate(_ allowed: Bool) {
        allowUnverifiedCertificate = allowed
        credentials.setAllowUnverifiedCertificate(allowed)
        if allowed { trustSwitchIsTheRemedy = false }
    }

    func clearMessage() {
        message = nil
        trustSwitchIsTheRemedy = false
        failureKind = nil
        if phase == .failed { phase = .idle }
    }

    private func refuse(_ refusal: ConnectionCopy.LocalRefusal) -> Bool {
        message = refusal.text
        phase = .failed
        failureKind = nil
        trustSwitchIsTheRemedy = false
        return false
    }
}
