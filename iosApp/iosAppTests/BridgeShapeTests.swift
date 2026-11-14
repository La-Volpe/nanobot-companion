import Foundation
import Testing
import core

/// What Swift really gets from the Kotlin framework (C8.2, D38). `BridgeShapeTest` asserts the
/// header's shape; this asserts the other half — that the shape compiles and behaves when a
/// Swift file uses it the way the app will. A toolchain upgrade that changes interop fails here
/// before a screen does.
@MainActor
struct BridgeShapeTests {

    private func companion() -> Companion {
        // Nothing is dialled until connect(); this only composes the client and the core.
        CompanionBridgeKt.openCompanion(baseUrl: "http://127.0.0.1:1", secret: "not-a-secret", allowUnverifiedCertificate: false)
    }

    @Test func aStateFlowArrivesTypedThroughTheBridge() {
        let companion = companion()
        defer { companion.close() }
        let state = ObservableState<ConnectionState>(flow: companion.core.connection)

        let value: ConnectionState = state.value
        #expect(value is ConnectionStateIdle, "a fresh core is idle, and the type is not Any")
    }

    @Test func subscribeDeliversTheCurrentValueImmediately() {
        let companion = companion()
        defer { companion.close() }
        let state = ObservableState<ConnectionState>(flow: companion.core.connection)

        var seen: [ConnectionState] = []
        let cancellable = state.subscribe { seen.append($0) }
        defer { cancellable.cancel() }

        #expect(seen.count == 1, "the first value arrives before the call returns, so a view has state on its first body")
        #expect(seen.first is ConnectionStateIdle)
    }

    /// The eight taxonomy entries are switched on by their lowercased Swift names (D38). This is
    /// the switch the connect screen writes, and the copy key is what it looks the sentence up by.
    @Test func theEightTaxonomyEntriesSwitchOnTheirSwiftNames() {
        let entries: [ConnectionFailure] = [
            ConnectionFailure.authfailed,
            ConnectionFailure.tlsrejected,
            ConnectionFailure.unreachable,
            ConnectionFailure.timeout,
            ConnectionFailure.protocoltoonew,
            ConnectionFailure.missingcapability,
            ConnectionFailure.missingfield,
            ConnectionFailure.servererror,
        ]

        let keys = entries.map { failure -> String in
            switch failure {
            case ConnectionFailure.authfailed: "connection.authFailed"
            case ConnectionFailure.tlsrejected: "connection.tlsRejected"
            case ConnectionFailure.unreachable: "connection.unreachable"
            case ConnectionFailure.timeout: "connection.timeout"
            case ConnectionFailure.protocoltoonew: "connection.protocolTooNew"
            case ConnectionFailure.missingcapability: "connection.missingCapability"
            case ConnectionFailure.missingfield: "connection.missingField"
            default: "connection.serverError"
            }
        }

        #expect(keys == entries.map(\.copyKey), "a Swift switch selects the same sentence the core named")
        #expect(ConnectionFailure.authfailed.retryable == false, "a refused key is not retried")
        #expect(ConnectionFailure.unreachable.retryable, "an unreachable gateway is")
    }

    @Test func sealedResultsCastToOneClassPerCase() {
        let connected: ConnectResult = ConnectResultConnected(modelName: "deepseek")
        #expect((connected as? ConnectResultConnected)?.modelName == "deepseek")

        let rejected: SendReceipt = SendReceiptRejected(reason: SendRejection.frametoolarge)
        #expect((rejected as? SendReceiptRejected)?.reason.copyKey == "send.frameTooLarge")

        let state: ConnectionState = ConnectionStateFailed(
            failure: ConnectionFailure.authfailed,
            retryable: false,
            detail: nil
        )
        #expect((state as? ConnectionStateFailed)?.failure.copyKey == "connection.authFailed")
    }

    /// A refused connection is the cheapest real call: no gateway, no timeout, and it proves the
    /// completion handler surfaces the typed result rather than trapping (D38.2).
    /// The switch is a parameter of the one call that builds the client, so a screen cannot
    /// open a stack and forget to pass the user's decision (C3.2, D21).
    @Test func theTrustSwitchReachesTheFactory() {
        let trusting = CompanionBridgeKt.openCompanion(
            baseUrl: "https://home.lan:8443",
            secret: "not-a-secret",
            allowUnverifiedCertificate: true
        )
        defer { trusting.close() }
        #expect(trusting.core.profile.url == "https://home.lan:8443")
    }

    @Test func aRealCallReturnsATypedResultInsteadOfTrapping() async throws {
        let companion = companion()
        defer { companion.close() }

        let result = try await companion.core.connect()

        #expect(result is ConnectResultFailed, "port 1 answers nothing, so the connect fails in the taxonomy")
        let failure = (result as? ConnectResultFailed)?.error
        #expect(failure is CoreErrorUnreachable || failure is CoreErrorTimeout, "a refused socket is the taxonomy's own word for it")
    }
}
