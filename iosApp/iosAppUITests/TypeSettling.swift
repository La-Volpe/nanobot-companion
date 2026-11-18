import XCTest

extension XCUIElement {
    /// Types `text` when the screen is redrawing under the cursor, and keeps going until the field
    /// holds exactly it.
    ///
    /// `typeText` posts every keystroke as fast as the runner can, and a field inside a view that
    /// is re-rendering — the composer redraws its transcription arc ten times a second — drops
    /// events under that pace: the hosted release run 44336245077 saw "ty" out of "typed while
    /// waiting". A tap can be swallowed the same way, which is what "Neither element nor any
    /// descendant has keyboard focus" meant in run 44393023565.
    ///
    /// So this reads the field, types only the part that is still missing, and measures again:
    /// a dropped keystroke costs a retry and a half-landed chunk is finished rather than
    /// duplicated. What the test asserts is unchanged — the field ends up holding exactly what
    /// was typed. Same shape as AGENTS gotcha 21: the instrument, not the app, is what races.
    func typeSettling(_ text: String, chunk: Int = 4, timeout: TimeInterval = 25) {
        tap()
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            // An empty field reports its placeholder as the value, so "Type a message…" has to
            // read as nothing typed rather than as text this call did not write.
            let raw = (value as? String) ?? ""
            let current = raw == placeholderValue ? "" : raw
            if current == text { return }
            guard text.hasPrefix(current) else {
                // Not a prefix means the field holds something this call did not type — a stray
                // caret, or the app appended text of its own. Typing into that would hide the
                // difference, so say it out loud instead.
                XCTFail("the field reads '\(current)', which is not a prefix of '\(text)'")
                return
            }
            guard Date() < deadline else {
                XCTFail("the field stopped at '\(current)' of '\(text)'")
                return
            }
            let missing = String(text[text.index(text.startIndex, offsetBy: current.count)...])
            let attempting = String(missing.prefix(chunk))
            typeText(attempting)
            // Give the chunk a moment to land; the next pass measures what actually arrived.
            let expected = NSPredicate(format: "value == %@", current + attempting)
            _ = XCTWaiter().wait(
                for: [XCTNSPredicateExpectation(predicate: expected, object: self)],
                timeout: 10
            )
        }
    }
}
