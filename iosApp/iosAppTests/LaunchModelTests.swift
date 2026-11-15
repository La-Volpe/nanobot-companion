import Foundation
import Testing
@testable import iosApp

/// Which screen a launch opens on, and why. The store is the reason a second launch is not a second
/// setup; a gateway that stopped answering is the reason a failed one is not onboarding either.
@MainActor
struct LaunchModelTests {

    /// A gateway that answers what the test says, and counts the attempts.
    final class FakeGateway: GatewayConnecting {
        var outcome: GatewayOutcome = .connected(model: "m")
        private(set) var calls = 0
        private(set) var disconnects = 0

        func connect(address: String, key: String, allowUnverifiedCertificate: Bool) async -> GatewayOutcome {
            calls += 1
            return outcome
        }

        func disconnect() {
            disconnects += 1
        }
    }

    private func launch(
        credentials: InMemoryCredentials = InMemoryCredentials(),
        startsConnected: Bool = false,
        prefilledAddress: String? = nil,
        prefilledKey: String? = nil
    ) -> (LaunchModel, ConnectViewModel, FakeGateway) {
        let gateway = FakeGateway()
        let connect = ConnectViewModel(
            gateway: gateway,
            credentials: credentials,
            prefilledAddress: prefilledAddress,
            prefilledKey: prefilledKey
        )
        let model = LaunchModel(
            startsConnected: startsConnected,
            hasStoredGateway: { connect.resolveStoredGateway() },
            connect: { await connect.reopenStoredGateway() }
        )
        return (model, connect, gateway)
    }

    private func gateway(at address: String = "http://home.lan:18765", key: String = "the-secret") -> InMemoryCredentials {
        let credentials = InMemoryCredentials()
        credentials.remember(address: address, key: key, allowUnverifiedCertificate: false)
        return credentials
    }

    @MainActor
    @Test func aStoredGatewayOpensTheChatWithoutAsking() async {
        let (model, connect, gateway) = launch(credentials: gateway())

        // The first paint is neither the question nor a guess: onboarding shown for a moment is
        // onboarding shown.
        #expect(model.screen == .deciding)

        await model.begin()

        #expect(model.screen == .shell, "a gateway the device already holds is not a first launch")
        #expect(gateway.calls == 1, "the stored gateway is reopened, not assumed")
        #expect(connect.phase == .connected(model: "m"))
    }

    /// The failure case: the address and the key are right, the gateway is not there. The banner and
    /// its retry belong to the shell, so that is where this lands.
    @Test func aStoredGatewayThatDoesNotAnswerIsTheBannerNotOnboarding() async {
        let (model, connect, fake) = launch(credentials: gateway())
        fake.outcome = .failed(.unreachable, detail: nil)

        await model.begin()

        #expect(fake.calls == 1, "it tried instead of assuming")
        #expect(connect.phase == .failed)
        #expect(model.screen == .shell, "onboarding is not where a gateway that stopped answering goes")
        #expect(fake.disconnects == 0, "the stack stays open, or the banner's retry has nothing to retry")
    }

    @Test func nothingStoredAsksForTheGateway() async {
        let (model, _, fake) = launch()

        await model.begin()

        #expect(model.screen == .onboarding)
        #expect(fake.calls == 0, "a first launch has nothing to attempt")
    }

    @Test func halfASetupIsStillAskingForTheGateway() async {
        // An address and no key is what a half-finished first attempt leaves behind.
        let (model, _, fake) = launch(credentials: InMemoryCredentials(address: "http://home.lan:18765"))

        await model.begin()

        #expect(model.screen == .onboarding, "an address without a key is the connect screen's job")
        #expect(fake.calls == 0)
    }

    /// `tools/run-on-device.sh` names the gateway in the launch environment. That launch is the same
    /// launch: it opens the chat, and what it connects with is stored for the tap on the icon after.
    @Test func aGatewayTheLaunchCarriesIsOpenedToo() async {
        let (model, _, fake) = launch(
            prefilledAddress: "http://127.0.0.1:18765",
            prefilledKey: "the-secret"
        )

        await model.begin()

        #expect(model.screen == .shell)
        #expect(fake.calls == 1)
    }

    /// A Keychain read that ran before the owner unlocked the phone comes back empty. Coming to the
    /// foreground is the second look.
    @Test func aSecondLookAtTheStoreCanStillFindTheGateway() async {
        let credentials = InMemoryCredentials()
        let (model, _, fake) = launch(credentials: credentials)

        await model.begin()
        #expect(model.screen == .onboarding)

        credentials.remember(address: "http://home.lan:18765", key: "the-secret", allowUnverifiedCertificate: false)
        await model.resumed()

        #expect(model.screen == .shell, "a launch that began without a readable store can still reopen")
        #expect(fake.calls == 1)
    }

    /// The other half of the same rule: a second look never overwrites what the owner has typed.
    @Test func aSecondLookLeavesATypedScreenAlone() async {
        let credentials = InMemoryCredentials()
        let (model, connect, fake) = launch(credentials: credentials)
        await model.begin()

        connect.address = "http://typed.lan:18765"
        connect.key = "typed-secret"
        credentials.remember(address: "http://stored.lan:18765", key: "stored-secret", allowUnverifiedCertificate: false)
        await model.resumed()

        #expect(connect.address == "http://typed.lan:18765", "what the owner typed is theirs")
        #expect(connect.key == "typed-secret")
        #expect(model.screen == .onboarding)
        #expect(fake.calls == 0, "a half-typed setup is not connected behind the owner's back")
    }

    /// The connect screen's success is the other way in, and disconnecting is the one way back.
    @Test func onboardingAndDisconnectMoveTheScreen() async {
        let (model, _, _) = launch()
        await model.begin()

        model.finishedOnboarding()
        #expect(model.screen == .shell)

        model.signedOut()
        #expect(model.screen == .onboarding)

        await model.resumed()
        #expect(model.screen == .onboarding, "the store is empty after a disconnect")
    }

    @Test func aScriptedLaunchStartsOnTheShell() async {
        let (model, _, fake) = launch(startsConnected: true)

        await model.begin()

        #expect(model.screen == .shell)
        #expect(fake.calls == 0, "the scripted gateway is already answered")
    }

    /// Disconnecting is the owner's decision. The foreground's second look is for a launch that
    /// found nothing, not a way back into a gateway they just left.
    @Test func aDisconnectIsNotUndoneByComingBackToTheForeground() async {
        let (model, _, _) = launch(startsConnected: true)
        await model.begin()
        model.signedOut()

        await model.resumed()

        #expect(model.screen == .onboarding)
    }
}
