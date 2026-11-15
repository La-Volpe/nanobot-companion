import Testing
@testable import iosApp

/// The two ways a phone asks for a socket it lost: the banner's "Try again" and coming back to
/// the foreground. What is asserted here is the state machine's rules — one attempt at a time, no
/// attempt on a socket that was never up, and the reads only after a handshake that succeeded.
@MainActor
struct ConnectionRecoveryTests {

    /// A connect that suspends until the test lets it go, so "one at a time" is a fact rather
    /// than a race the test happens to win.
    private actor Gate {
        private var waiters: [CheckedContinuation<Void, Never>] = []
        private(set) var waiting = 0

        func hold() async {
            waiting += 1
            await withCheckedContinuation { waiters.append($0) }
        }

        func open() {
            waiters.forEach { $0.resume() }
            waiters.removeAll()
        }
    }

    private final class Counter {
        var connects = 0
        var reloads = 0
    }

    private func model(
        _ counter: Counter,
        gate: Gate? = nil,
        succeeds: Bool = true,
        onReload: (() -> Void)? = nil
    ) -> ConnectionRecovery {
        let model = ConnectionRecovery(reconnect: {
            counter.connects += 1
            if let gate { await gate.hold() }
            return succeeds
        })
        model.reload = {
            counter.reloads += 1
            onReload?()
        }
        return model
    }

    @Test func aRetryReconnectsAndThenReadsAgain() async {
        let counter = Counter()
        let model = model(counter)

        await model.retry(status: .failed(.timeout, detail: nil))

        #expect(counter.connects == 1, "the button asks the core for a new socket, a new token and all")
        #expect(counter.reloads == 1, "the reads run on the api_token the new handshake minted")
    }

    @Test func aFailedRetryReadsNothing() async {
        let counter = Counter()
        let model = model(counter, succeeds: false)

        await model.retry(status: .failed(.unreachable, detail: nil))

        #expect(counter.connects == 1)
        #expect(counter.reloads == 0, "a handshake that failed minted no token to read with")
    }

    @Test func aSecondTapDoesNotRaceTheFirstConnect() async {
        let counter = Counter()
        let gate = Gate()
        let model = model(counter, gate: gate)

        let first = Task { await model.retry(status: .failed(.timeout, detail: nil)) }
        while await gate.waiting == 0 { await Task.yield() }

        // The second tap is fired, not awaited: a model that let it through would hold here
        // until the gate opens, so the assertion below is what catches it, not a hang.
        let second = Task { await model.retry(status: .failed(.timeout, detail: nil)) }
        for _ in 0 ..< 50 { await Task.yield() }
        #expect(counter.connects == 1, "a second handshake racing the first is how a token gets burned twice")

        await gate.open()
        await first.value
        await second.value
        #expect(counter.reloads == 1)
    }

    @Test func comingBackToTheForegroundReconnectsASocketThatDropped() async {
        let counter = Counter()
        let model = model(counter)

        await model.resumed(status: .offline, everConnected: true)
        #expect(counter.connects == 1, "the socket died while the app was suspended")

        await model.resumed(status: .failed(.unreachable, detail: nil), everConnected: true)
        #expect(counter.connects == 2, "and a suspended socket that failed is the same case")
    }

    @Test func aPhoneThatNeverConnectedStaysWhereItIs() async {
        let counter = Counter()
        let model = model(counter)

        await model.resumed(status: .idle, everConnected: false)

        #expect(counter.connects == 0, "onboarding is not a socket to recover")
    }

    @Test func aForegroundKeepsASocketThatIsUp() async {
        let counter = Counter()
        let model = model(counter)

        await model.resumed(status: .open, everConnected: true)

        #expect(counter.connects == 0, "nothing to recover on a socket that is up")
    }

    /// The button itself is unpersuaded by the connection's state: it is only drawn under a
    /// banner that says the socket is lost, and an injected failure (the debug menu) is exactly
    /// the case where the state says open and the screen says otherwise.
    @Test func theButtonAsksForASocketWhateverTheStateSays() async {
        let counter = Counter()
        let model = model(counter)

        await model.retry(status: .open)

        #expect(counter.connects == 1)
    }

    @Test func aConnectAlreadyRunningIsNotFought() async {
        let counter = Counter()
        let model = model(counter)

        await model.resumed(status: .connecting, everConnected: true)
        await model.retry(status: .connecting)

        #expect(counter.connects == 0, "the answer to the connect in flight is already coming")
    }
}
