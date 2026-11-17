import Foundation

/// The composition root (the `CoreHolder` plus the two stores). Every screen takes
/// what it needs from here rather than reaching for a singleton, which is what lets the UI
/// tests launch the app against a scripted gateway instead of a real one.
@MainActor
final class Container {
    let holder: CoreHolder
    let gateway: GatewayConnecting
    let credentials: CredentialsStoring

    /// The phone half of a task notice (the rule is the core's), owned here so the delegate
    /// exists before the first frame and a tap works on a cold launch too.
    let notices: TaskNoticeCenter

    /// The one implementation of the system's notification calls: banners and Settings both use it.
    let notifications: SystemNotificationCentre

    /// Where the voice hint's "shown once" lives: the real store on a phone, a throwing-away
    /// one for a scripted launch, so every UI test starts with the hint unshown.
    let voiceHints: VoiceHints

    /// A scripted chat for a flow test (DEBUG only): the real one needs a gateway.
    var scriptedChat: ChatDriving?
    /// The gateway key a live-session launch prefills (DEBUG only): typed keys land in test logs.
    var prefilledKey: String?
    /// The gateway address the same launch carries. It is a prefill, not a replacement for the
    /// store: a connect through it is stored like any other, so the launch after the instrument is
    /// the owner's own and does not ask again.
    var prefilledAddress: String?
    /// Set when the launch asked for a scripted thread; the shell builds the chat around this.
    var scripted = false

    /// The markdown fixture launch (`NANOBOT_SCENARIO=markdown`): the chat streams
    /// `ScriptedChat.document` instead of answering, which is how a construct no gateway reply
    /// happens to contain still reaches the renderers.
    var markdownFixture = false

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
        prefilledKey: String? = nil,
        voiceHints: VoiceHints? = nil
    ) {
        self.prefilledKey = prefilledKey
        self.startsConnected = startsConnected
        self.voiceHints = voiceHints ?? UserDefaultsVoiceHints(defaults: .standard)
        let holder = holder ?? CoreHolder()
        self.holder = holder
        let centre = SystemNotificationCentre()
        self.notifications = centre
        // The switches the Notifications screen writes are the ones the poster checks, read at post
        // time from the same store the screen writes.
        self.notices = TaskNoticeCenter(
            posting: centre,
            switches: { NoticeSwitchStore.switches(in: UserDefaults.standard) }
        )
        holder.notices = self.notices
        // A tap is the shell's business: it is who can put a chat on screen.
        centre.onOpenChat = { [weak holder] chatId in holder?.requestChatFromNotification(chatId) }
        self.gateway = gateway ?? CoreGateway(holder: holder)
        self.credentials = credentials ?? StoredCredentials(secrets: KeychainSecrets())
    }

    /// What a launch gets. A UI test (or, later, the debug menu) names a scenario
    /// in the environment and gets the scripted gateway with stores that write nothing.
    static func forLaunch() -> Container {
        #if DEBUG
        let environment = ProcessInfo.processInfo.environment
        // A reset launch: the store is emptied before the screens exist, so an instrument that
        // tests "first launch" starts from a first launch instead of yesterday's Keychain.
        let reset = environment["NANOBOT_FORGET"] != nil
        // The live-session instrument: a real gateway, a real socket, and the key through the
        // environment rather than a typed string in the test log. The store is the production one,
        // because the run after this launch is a plain launch from the home screen and it has to
        // find what this one used.
        if let url = environment["NANOBOT_LIVE_URL"] {
            let container = Container(prefilledKey: environment["NANOBOT_LIVE_SECRET"])
            if reset { container.credentials.forget() }
            container.prefilledAddress = url
            return container
        }
        if reset {
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
                startsConnected: environment["NANOBOT_START_CONNECTED"] != nil,
                // The hint is a once-ever sentence, so a scripted launch that has to observe it
                // needs a store that has never said it.
                voiceHints: InMemoryVoiceHints()
            )
            if environment["NANOBOT_SESSIONS"] != nil {
                container.holder.seedSessionsForUITest(SessionSummary.samples)
            }
            // A scripted gateway answers the settings read too, so a tap can walk Overview.
            container.holder.seedSettingsForUITest(.scriptedGateway)
            // And its model sheet lists the three presets the owner's gateway lists, built-in one
            // included: a tap proves which of them a phone offers.
            container.holder.seedPresetsForUITest()
            if environment["NANOBOT_THREAD"] != nil || scripted == .markdown {
                // The chat is created by the shell, which is where its callback lands.
                container.scripted = true
            }
            container.markdownFixture = scripted == .markdown
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
