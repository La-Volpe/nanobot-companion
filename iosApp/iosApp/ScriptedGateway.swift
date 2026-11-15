#if DEBUG
import Foundation

/// A gateway that answers what it is told to answer, for the flows a tap can prove
/// (`ConnectFlowTests`) and for the debug menu G2 asks for — the eight error states each
/// reachable without a broken gateway, a wrong key or a certificate that expired.
///
/// Compiled only in Debug: a release build has no way to reach it.
@MainActor
final class ScriptedGateway: GatewayConnecting {

    enum Scenario: String, CaseIterable {
        case connected
        case authFailed
        case tlsRejected
        case unreachable
        case timeout
        case protocolTooNew
        case missingCapability
        case missingField
        case serverError
        /// Answers with whatever the switch says: a self-signed gateway that only works once
        /// the user allows an unverified certificate.
        case selfSigned
        /// A connected shell whose chat streams the markdown fixture (`ScriptedChat.document`),
        /// so every construct the core parses can be seen on a device without a model in the
        /// loop: the parser, the split and both renderers are the real ones.
        case markdown
    }

    private let scenario: Scenario
    private(set) var lastAllowUnverified: Bool?

    init(scenario: Scenario) {
        self.scenario = scenario
    }

    func connect(address: String, key: String, allowUnverifiedCertificate: Bool) async -> GatewayOutcome {
        lastAllowUnverified = allowUnverifiedCertificate
        switch scenario {
        case .connected, .markdown: return .connected(model: "scripted-model")
        case .authFailed: return .failed(.authFailed, detail: nil)
        case .tlsRejected: return .failed(.tlsRejected, detail: nil)
        case .unreachable: return .failed(.unreachable, detail: nil)
        case .timeout: return .failed(.timeout, detail: nil)
        case .protocolTooNew: return .failed(.protocolTooNew, detail: "2/1")
        case .missingCapability: return .failed(.missingCapability, detail: "webui.subagents.v1")
        case .missingField: return .failed(.missingField, detail: "token")
        case .serverError: return .failed(.serverError, detail: "503")
        case .selfSigned:
            return allowUnverifiedCertificate ? .connected(model: "home-gateway") : .failed(.tlsRejected, detail: nil)
        }
    }

    func disconnect() {}
}
#endif
