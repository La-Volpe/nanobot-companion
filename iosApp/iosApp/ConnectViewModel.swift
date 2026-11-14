import Foundation
import SwiftUI

/// Onboarding's three fields, one button and its states (plan.md I1, design artifact
/// `data-view="onboarding"`). Every branch worth testing is here rather than in the view:
/// what counts as a usable address, when the key is required, what a failure says, and the one
/// rule the unverified-certificate switch exists for — it is the user's decision, never the
/// app's.
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

    /// `prefilledKey` exists for the live session instrument (DEBUG only): the gateway secret
    /// arrives through the launch environment instead of being typed, so it never reaches a
    /// test log.
    init(gateway: GatewayConnecting, credentials: CredentialsStoring, prefilledKey: String? = nil) {
        self.gateway = gateway
        self.credentials = credentials
        self.address = credentials.address ?? ""
        // The store's key is the whole point of remembering: a launch that has one reconnects
        // instead of asking the user to type a secret the app already holds.
        self.key = prefilledKey ?? credentials.storedKey ?? ""
        self.allowUnverifiedCertificate = credentials.allowUnverifiedCertificate
    }

    var isConnecting: Bool { phase == .connecting }

    var canConnect: Bool {
        !isConnecting && !address.trimmingCharacters(in: .whitespaces).isEmpty && !key.isEmpty
    }

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
        guard !isConnecting else { return }
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
        case let .failed(failure, detail):
            failureKind = failure
            trustSwitchIsTheRemedy = failure == .tlsRejected && !allowUnverifiedCertificate
            message = ConnectionCopy.text(for: failure, detail: detail)
            phase = .failed
            gateway.disconnect()
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

    private func refuse(_ refusal: ConnectionCopy.LocalRefusal) {
        message = refusal.text
        phase = .failed
        failureKind = nil
        trustSwitchIsTheRemedy = false
    }
}
