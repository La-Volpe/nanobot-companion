import Foundation

/// The composition root (plan.md §7's `CoreHolder` plus the two stores). Every screen takes
/// what it needs from here rather than reaching for a singleton, which is what lets the UI
/// tests launch the app against a scripted gateway instead of a real one.
@MainActor
final class Container {
    let holder: CoreHolder
    let gateway: GatewayConnecting
    let credentials: CredentialsStoring

    /// A scripted chat for a flow test (DEBUG only): the real one needs a gateway.
    var scriptedChat: ChatDriving?
    /// The gateway key a live-session launch prefills (DEBUG only): typed keys land in test logs.
    var prefilledKey: String?
    /// Set when the launch asked for a scripted thread; the shell builds the chat around this.
    var scripted = false

    /// Set when the launch asked the composer to open with attachments on screen. The system
    /// photo picker cannot be driven by a UI test, so this is how the cards' own states are
    /// proved: a pick that landed, and one whose upload failed.
    var scriptedAttachments = false

    /// A scripted launch can start on the connected shell, which is what a drawer or chat flow
    /// test needs: onboarding is already covered by its own suite.
    let startsConnected: Bool

    init(
        holder: CoreHolder? = nil,
        gateway: GatewayConnecting? = nil,
        credentials: CredentialsStoring? = nil,
        startsConnected: Bool = false,
        prefilledKey: String? = nil
    ) {
        self.prefilledKey = prefilledKey
        self.startsConnected = startsConnected
        let holder = holder ?? CoreHolder()
        self.holder = holder
        self.gateway = gateway ?? CoreGateway(holder: holder)
        self.credentials = credentials ?? StoredCredentials(secrets: KeychainSecrets())
    }

    /// What a launch gets. A UI test (or, later, the debug menu G2 asks for) names a scenario
    /// in the environment and gets the scripted gateway with stores that write nothing.
    static func forLaunch() -> Container {
        #if DEBUG
        let environment = ProcessInfo.processInfo.environment
        // The live-session instrument: a real gateway, a real socket, and the key through the
        // environment rather than a typed string in the test log.
        if let url = environment["NANOBOT_LIVE_URL"] {
            return Container(
                credentials: InMemoryCredentials(
                    address: url,
                    allowUnverifiedCertificate: environment["NANOBOT_LIVE_UNVERIFIED"] != nil
                ),
                prefilledKey: environment["NANOBOT_LIVE_SECRET"]
            )
        }
        // A reset launch: the store is emptied before the screens exist, so an instrument that
        // tests "first launch" starts from a first launch instead of yesterday's Keychain.
        if environment["NANOBOT_FORGET"] != nil {
            let container = Container()
            container.credentials.forget()
            return container
        }
        if let scenario = environment["NANOBOT_SCENARIO"], let scripted = ScriptedGateway.Scenario(rawValue: scenario) {
            let container = Container(
                gateway: ScriptedGateway(scenario: scripted),
                credentials: InMemoryCredentials(
                    address: environment["NANOBOT_ADDRESS"],
                    allowUnverifiedCertificate: false
                ),
                startsConnected: environment["NANOBOT_START_CONNECTED"] != nil
            )
            if environment["NANOBOT_SESSIONS"] != nil {
                container.holder.seedSessionsForUITest(SessionSummary.samples)
            }
            if environment["NANOBOT_THREAD"] != nil {
                // The chat is created by the shell, which is where its callback lands.
                container.scripted = true
            }
            container.scriptedAttachments = environment["NANOBOT_ATTACH"] != nil
            return container
        }
        #endif
        return Container()
    }
}

/// The credentials a UI test uses: real behaviour, no persistence, nothing to clean up between
/// runs and no Keychain prompt in a test bundle.
final class InMemoryCredentials: CredentialsStoring {
    private(set) var address: String?
    private(set) var allowUnverifiedCertificate: Bool
    private(set) var storedKey: String?

    init(address: String? = nil, allowUnverifiedCertificate: Bool = false) {
        self.address = address
        self.allowUnverifiedCertificate = allowUnverifiedCertificate
    }

    func remember(address: String, key: String, allowUnverifiedCertificate: Bool) {
        self.address = address
        self.storedKey = key
        self.allowUnverifiedCertificate = allowUnverifiedCertificate
    }

    func setAllowUnverifiedCertificate(_ allowed: Bool) {
        allowUnverifiedCertificate = allowed
    }

    func forget() {
        address = nil
        storedKey = nil
        allowUnverifiedCertificate = false
    }
}
