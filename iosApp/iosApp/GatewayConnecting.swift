import Foundation

/// What onboarding's one command comes back with, in Swift's own words. The view model talks
/// to this, never to the framework: `CoreGateway` (the adapter layer) is the translation, so
/// this whole screen is testable without a gateway, a simulator build or a Kotlin type.
enum GatewayOutcome: Equatable {
    case connected(model: String?)
    case failed(ConnectionFailureKind, detail: String?)
}

/// The one thing the connect screen needs from the core (plan.md §7: views read one state and
/// call one command). One method, so a test double is a closure rather than a fake framework.
@MainActor
protocol GatewayConnecting: AnyObject {
    func connect(address: String, key: String, allowUnverifiedCertificate: Bool) async -> GatewayOutcome
    func disconnect()
}
