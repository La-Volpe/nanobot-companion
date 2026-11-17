import Foundation
import Testing
@testable import iosApp

/// Onboarding's branches: what is refused locally, what each failure says, and the rule the
/// unverified-certificate switch exists for, which is that the app never turns it on itself.
@MainActor
struct ConnectViewModelTests {

    /// A gateway that answers what the test says, and records what it was asked.
    final class FakeGateway: GatewayConnecting {
        var outcome: GatewayOutcome = .connected(model: "m")
        private(set) var calls: [(address: String, key: String, allowUnverified: Bool)] = []
        private(set) var disconnects = 0

        func connect(address: String, key: String, allowUnverifiedCertificate: Bool) async -> GatewayOutcome {
            calls.append((address, key, allowUnverifiedCertificate))
            return outcome
        }

        func disconnect() {
            disconnects += 1
        }
    }

    private func model(
        _ outcome: GatewayOutcome = .connected(model: "m"),
        credentials: InMemoryCredentials = InMemoryCredentials()
    ) -> (ConnectViewModel, FakeGateway, InMemoryCredentials) {
        let gateway = FakeGateway()
        gateway.outcome = outcome
        let model = ConnectViewModel(gateway: gateway, credentials: credentials)
        model.address = "https://home.lan:8443"
        model.key = "the-secret"
        return (model, gateway, credentials)
    }

    @Test func aFailedConnectNamesTheFailureInsteadOfTryingAgain() async {
        let (model, gateway, _) = model(.failed(.authFailed, detail: nil))

        await model.connect()

        #expect(gateway.calls.count == 1, "one tap is one attempt")
        #expect(model.phase == .failed)
        #expect(model.message == ConnectionCopy.text(for: .authFailed, detail: nil))
        #expect(model.offersRetry == false, "a refused key is not retried; the sentence says what to fix")
        #expect(gateway.disconnects == 1, "a failed attempt does not leave a client behind")
    }

    /// A certificate the phone will not verify is the user's
    /// decision to make, so the app points at the switch instead of quietly trusting it.
    @Test func unverifiedCertRequiresExplicitToggle() async {
        let (model, gateway, _) = model(.failed(.tlsRejected, detail: nil))

        await model.connect()

        #expect(model.trustSwitchIsTheRemedy, "the TLS refusal is the one whose fix is on this screen")
        #expect(model.allowUnverifiedCertificate == false, "the app does not turn the switch on by itself")
        #expect(gateway.calls.first?.allowUnverified == false, "and it does not retry with trust enabled")

        model.setAllowUnverifiedCertificate(true)
        gateway.outcome = .connected(model: "home-gateway")
        await model.connect()

        #expect(gateway.calls.count == 2, "the second attempt is the user's, after the switch")
        #expect(gateway.calls.last?.allowUnverified == true, "the switch reaches the one call that builds the client")
        #expect(model.trustSwitchIsTheRemedy == false)
        #expect(model.phase == .connected(model: "home-gateway"))
    }

    @Test func authFailureShowsSecretCopy() async {
        let (model, _, credentials) = model(.failed(.authFailed, detail: nil))

        await model.connect()

        #expect(model.message?.contains("key was refused") == true, "the copy names the key, not the network")
        #expect(credentials.storedKey == nil, "a refused key is never made durable")
        #expect(credentials.address == nil)
    }

    @Test func everyFailureHasItsOwnCopyAndItsOwnRetryAnswer() async {
        var seen: [String] = []
        for kind in ConnectionFailureKind.allCases {
            let (model, _, _) = model(.failed(kind, detail: nil))
            await model.connect()
            let copy = model.message ?? ""
            #expect(!copy.isEmpty, "\(kind) has a sentence")
            #expect(model.offersRetry == kind.isRetryable, "\(kind) offers retry exactly when the core says so")
            seen.append(copy)
        }
        #expect(Set(seen).count == ConnectionFailureKind.allCases.count, "eight failures, eight different sentences")
    }

    @Test func a5xxSaysTryAgainAndA4xxDoesNot() {
        let server = ConnectionFailureKind.serverError
        #expect(ConnectionCopy.text(for: server, detail: "503").contains("Try again in a moment"))
        #expect(!ConnectionCopy.text(for: server, detail: "404").contains("Try again"))
    }

    @Test func anAddressWithoutASchemeIsRefusedBeforeAnyCall() async {
        let (model, gateway, _) = model()
        model.address = "192.168.1.10:8787"

        await model.connect()

        #expect(gateway.calls.isEmpty, "the phone refuses what the handshake would refuse, for free")
        #expect(model.message == ConnectionCopy.LocalRefusal.addressNeedsScheme.text)
    }

    @Test func aMissingKeyIsRefusedBeforeAnyCall() async {
        let (model, gateway, _) = model()
        model.key = ""

        await model.connect()

        #expect(gateway.calls.isEmpty)
        #expect(model.message == ConnectionCopy.LocalRefusal.keyMissing.text)
        #expect(model.canConnect == false)
    }

    @Test func aSuccessfulConnectRemembersTheAddressAndTheKey() async {
        let (model, _, credentials) = model(.connected(model: "deepseek"))

        await model.connect()

        #expect(model.phase == .connected(model: "deepseek"))
        #expect(credentials.address == "https://home.lan:8443")
        #expect(credentials.storedKey == "the-secret")
        model.address = " https://home.lan:8443 "
        await model.connect()
        #expect(credentials.address == "https://home.lan:8443", "the remembered address is the trimmed one")
    }

    /// A gateway that hangs until the test lets it answer, so "while connecting" is a state the
    /// test can stand in rather than a race it hopes to hit.
    final class HangingGateway: GatewayConnecting {
        private(set) var calls = 0
        private var release: CheckedContinuation<Void, Never>?

        func connect(address: String, key: String, allowUnverifiedCertificate: Bool) async -> GatewayOutcome {
            calls += 1
            await withCheckedContinuation { release = $0 }
            return .connected(model: "slow")
        }

        func finish() {
            release?.resume()
            release = nil
        }

        func disconnect() {}
    }

    @Test func aSecondTapWhileConnectingIsIgnored() async {
        let gateway = HangingGateway()
        let model = ConnectViewModel(gateway: gateway, credentials: InMemoryCredentials())
        model.address = "https://home.lan"
        model.key = "secret"

        let first = Task { await model.connect() }
        while gateway.calls == 0 { await Task.yield() }
        #expect(model.isConnecting, "the button shows Connecting while the gateway is thinking")

        await model.connect()
        gateway.finish()
        await first.value

        #expect(gateway.calls == 1, "a second tap while connecting is not a second attempt")
        #expect(model.phase == .connected(model: "slow"))
    }
}

/// The copy and the core's taxonomy have to stay in step: the keys are asserted here, and the
/// framework-side mapping is asserted by `BridgeShapeTests`.
struct ConnectionCopyTests {

    @Test func theEightKeysAreTheCores() {
        #expect(ConnectionFailureKind.allCases.map(\.copyKey) == [
            "connection.authFailed",
            "connection.tlsRejected",
            "connection.unreachable",
            "connection.timeout",
            "connection.protocolTooNew",
            "connection.missingCapability",
            "connection.missingField",
            "connection.serverError",
        ])
    }

    @Test func retryabilityMatchesTheCoresDecision() {
        #expect(ConnectionFailureKind.allCases.filter(\.isRetryable) == [.unreachable, .timeout, .serverError])
    }
}
