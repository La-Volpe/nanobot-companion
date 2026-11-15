import Foundation

/// Which screen a launch opens on, and why. The store is the reason a second launch is not a
/// second setup: a gateway the device already holds puts the app in the chat, and a gateway that
/// no longer answers is the banner on that chat — with its own "Try again" — rather than the
/// address and key questions again.
///
/// This is a model for the same reason every other branch in the app is: the rule is worth a test,
/// and a rule living in a `.task` on a view cannot be asked which screen it chose.
@MainActor
final class LaunchModel: ObservableObject {

    enum Screen: Equatable {
        /// The store has not been read and the gateway has not answered yet. The first paint is
        /// neither the question nor a guess, which is what makes "it reopened" true and not
        /// almost-true: onboarding shown for a moment is onboarding shown.
        case deciding
        /// Nothing on this device can be reopened: the address and the key are the user's to type.
        case onboarding
        /// The chat, whether or not the socket is up — the shell says the rest.
        case shell
    }

    @Published private(set) var screen: Screen

    private let startsConnected: Bool
    /// Whether the device holds both halves of a gateway, resolved by the connect model, because
    /// it is the one place that knows a launch-carried address wins over a stored one. Asked again
    /// on a second look, since a Keychain read on a locked phone comes back empty.
    private let hasStoredGateway: () -> Bool
    /// One attempt on the stored gateway. What it returned is not needed here: the failure is the
    /// shell's banner, and the connect model is what put it there.
    private let connect: () async -> Void
    private var began = false
    /// Settings → Gateway → Disconnect is the owner's decision, not an unfinished launch: the
    /// second look a foreground makes is not a way back in behind their back.
    private var leftByTheOwner = false

    init(
        startsConnected: Bool,
        hasStoredGateway: @escaping () -> Bool,
        connect: @escaping () async -> Void
    ) {
        self.startsConnected = startsConnected
        self.hasStoredGateway = hasStoredGateway
        self.connect = connect
        self.screen = startsConnected ? .shell : .deciding
    }

    /// One launch decision, made before any screen can be drawn.
    func begin() async {
        guard !began else { return }
        began = true
        await decide()
    }

    /// Back to the foreground while this launch has still not found a gateway. A Keychain read can
    /// come back empty on a phone that was locked when it ran, so the store gets a second look once
    /// the owner is actually looking at the screen. Only an unanswered first launch is re-decided:
    /// what the owner typed on the connect screen is theirs, not ours.
    func resumed() async {
        guard began, !leftByTheOwner, screen == .onboarding else { return }
        await decide()
    }

    /// The connect screen's own success: the user typed a gateway this time.
    func finishedOnboarding() {
        began = true
        leftByTheOwner = false
        screen = .shell
    }

    /// Settings → Gateway → Disconnect forgot the store, so the questions come back.
    func signedOut() {
        leftByTheOwner = true
        screen = .onboarding
    }

    private func decide() async {
        if startsConnected {
            screen = .shell
            return
        }
        guard hasStoredGateway() else {
            screen = .onboarding
            return
        }
        // The attempt happens behind the deciding screen, so what the owner sees is the answer.
        await connect()
        // Reaching the shell is not the same claim as reaching the gateway: the shell draws the
        // connection state, so a gateway that stopped answering lands here with its sentence and
        // its retry instead of asking for an address it already has.
        screen = .shell
    }
}
