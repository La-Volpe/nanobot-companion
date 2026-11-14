import Foundation
import Testing
import core
@testable import iosApp

/// G3's third item, the iOS half (plan.md §8): the app opens the *same stored chat* the CLI
/// already has and prints the thread it holds as the CLI's plain lines, so
/// `tools/parity-check.sh` can diff the two mechanically instead of putting a phone and a
/// simulator side by side and looking at them.
///
/// This is the twin of Android's `LiveSessionTest.theSameStoredChatReadsBackAsTheTranscript`,
/// down to the line format: the socket's reduction in `core-cli watch`, the gateway's own page
/// through `HistoryReplay` in the app — one core, so a difference is an app-path bug.
///
/// Opt-in, like the live trio: without the three variables it is skipped rather than failed, so
/// the ordinary simulator suite needs no gateway. The lines go to `NANOBOT_PARITY_OUT` because
/// an `xcodebuild` log is not a diffable artifact.
@MainActor
struct ParityTranscriptTests {

    private var environment: [String: String] { ProcessInfo.processInfo.environment }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["NANOBOT_LIVE_CHAT"] != nil))
    func theSameStoredChatReadsBackAsTheTranscript() async throws {
        let url = try #require(environment["NANOBOT_LIVE_URL"], "NANOBOT_LIVE_URL")
        let secret = try #require(environment["NANOBOT_LIVE_SECRET"], "NANOBOT_LIVE_SECRET")
        let chatId = try #require(environment["NANOBOT_LIVE_CHAT"], "NANOBOT_LIVE_CHAT")
        let out = try #require(environment["NANOBOT_PARITY_OUT"], "NANOBOT_PARITY_OUT")

        let holder = CoreHolder()
        defer { holder.close() }
        holder.open(baseUrl: url, secret: secret, allowUnverifiedCertificate: false)
        let connected = try await holder.connect()
        #expect(connected is ConnectResultConnected, "the live gateway answered, not \(String(describing: connected))")

        let handle = try #require(holder.chat(id: chatId), "the core hands out a handle for the pinned chat")
        var thread = ChatThreadState()
        let chat = CoreChat(holder: holder, handle: handle, onChange: { state in thread = state })
        chat.start()
        defer { chat.stopObserving() }

        // The app's own way in: attach, then the WebUI's page read replayed through the same
        // reducer (D46). Nothing here reaches around the facade to the gateway's HTTP API.
        await chat.open()
        let deadline = Date().addingTimeInterval(90)
        while thread.turns.isEmpty && Date() < deadline {
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        #expect(!thread.turns.isEmpty, "the stored chat replayed its history into the app's thread")

        let lines = thread.turns.enumerated().map { index, turn in
            let prompt = (turn.prompt ?? "").replacingOccurrences(of: "\n", with: " ")
            let answer = turn.answer.replacingOccurrences(of: "\n", with: " ")
            let status = turn.running ? "Running" : "Complete"
            return "parity ios turn \(index + 1) | prompt=\(prompt) | answer=\(answer) | status=\(status)"
        }
        let body = lines.joined(separator: "\n") + "\n"
        try body.write(toFile: out, atomically: true, encoding: .utf8)
        lines.forEach { print($0) }
    }
}
