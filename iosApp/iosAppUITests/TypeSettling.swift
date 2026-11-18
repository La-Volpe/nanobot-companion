import XCTest

extension XCUIElement {
    /// Types `text` when the screen is redrawing under the cursor.
    ///
    /// `typeText` posts every keystroke as fast as the runner can, and a field inside a view that
    /// is re-rendering — the composer redraws its transcription arc ten times a second — drops
    /// events under that pace: the hosted release run 44336245077 saw "ty" out of "typed while
    /// waiting" on a three-core runner while the same tap here typed all of it. Typing in small
    /// chunks and waiting for each to land keeps what the test asserts (the field holds exactly
    /// what was typed) and removes the pace from the result. This is the same shape as AGENTS
    /// gotcha 21: the instrument, not the app, is what races.
    func typeSettling(_ text: String, chunk: Int = 4, timeout: TimeInterval = 10) {
        tap()
        var typed = ""
        for piece in stride(from: 0, to: text.count, by: chunk) {
            let start = text.index(text.startIndex, offsetBy: piece)
            let end = text.index(start, offsetBy: min(chunk, text.count - piece))
            let slice = String(text[start ..< end])
            typeText(slice)
            typed += slice
            let expected = NSPredicate(format: "value == %@", typed)
            let landed = XCTNSPredicateExpectation(predicate: expected, object: self)
            XCTAssertEqual(
                XCTWaiter().wait(for: [landed], timeout: timeout),
                .completed,
                "the field took '\(typed)' of '\(text)', so a keystroke was dropped"
            )
        }
    }
}
