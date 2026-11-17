import Foundation

/// The two ways the phone asks for a socket it has lost: the banner's "Try again", and
/// coming back to the foreground after the system suspended the app. Both end here because both
/// have the same two rules: one attempt at a time, and nothing to recover when there was never
/// a socket to begin with.
///
/// The rule that makes a retry worth offering at all: the core's `connect()` bootstraps a fresh
/// single-use token on every call, so a retry is a real handshake and not a replay of a dead one
/// (`transport/ConnectionAttempt.kt`).
@MainActor
final class ConnectionRecovery {

    /// The core's connect on the stack that is already open. True when the gateway greeted the
    /// new socket; a false leaves the failure on screen for the banner to say.
    private let reconnect: () async -> Bool

    /// The reads that need the `api_token` the new handshake minted, plus the chat the new socket
    /// has not been told about. Set once by the shell, because only the shell knows which chat is
    /// on screen.
    var reload: () async -> Void = {}

    private var running = false

    init(reconnect: @escaping () async -> Bool) {
        self.reconnect = reconnect
    }

    /// "Try again": the button exists only under a banner that says the socket is lost, and the
    /// user asked, so the state must not talk them out of it. What it will never do is race a
    /// connect already in flight, because two bootstraps is two sockets and a token burned twice.
    func retry(status: ConnectionStatus) async {
        guard status != .connecting else { return }
        await attempt()
    }

    /// Back from the background. A phone that never connected shows onboarding and wants nothing
    /// here; a socket that is open never went away; and a connect still running has its answer
    /// coming already.
    func resumed(status: ConnectionStatus, everConnected: Bool) async {
        guard everConnected, status != .open, status != .connecting else { return }
        await attempt()
    }

    private func attempt() async {
        guard !running else { return }
        running = true
        defer { running = false }
        if await reconnect() { await reload() }
    }
}
