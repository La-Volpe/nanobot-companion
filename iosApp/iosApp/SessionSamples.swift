#if DEBUG
import Foundation

extension SessionSummary {
    /// The rows a scripted launch shows (DEBUG only). They are shaped like recorded payloads:
    /// a title, a preview line, and nothing else the drawer reads.
    static let samples: [SessionSummary] = [
        SessionSummary(
            key: "websocket:11111111-1111-1111-1111-111111111111",
            chatId: "11111111-1111-1111-1111-111111111111",
            title: "Session store batching",
            preview: "Rework the session store so writes are batched",
            updatedAtMs: 4
        ),
        SessionSummary(
            key: "websocket:22222222-2222-2222-2222-222222222222",
            chatId: "22222222-2222-2222-2222-222222222222",
            title: "Deploy notes",
            preview: "nginx and tailscale on the VPS",
            updatedAtMs: 3
        ),
        SessionSummary(
            key: "websocket:33333333-3333-3333-3333-333333333333",
            chatId: "33333333-3333-3333-3333-333333333333",
            title: "Weekly review",
            preview: "What did the gateway do all week",
            updatedAtMs: 2
        ),
    ]
}
#endif
