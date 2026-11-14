import Testing
import SwiftUI
import UIKit
@testable import iosApp

/// The third G2 item has to be checkable, not claimed: the reading face is bundled and really
/// registers with CoreText, the interface face is the system's, and no control is under 44 pt.
struct DesignSystemTests {

    @Test func theReadingFaceIsOpenSansAndItIsRegistered() throws {
        #expect(ReadingType.family == "Open Sans")
        let font = try #require(
            UIFont(name: ReadingType.family, size: ReadingType.size),
            "Open Sans ships in Resources/ and is listed in UIAppFonts"
        )
        #expect(abs(font.pointSize - 17) < 0.01)
        #expect(font.fontName.contains("OpenSans"), "the registered face is the bundled one, not a system fallback: \(font.fontName)")
    }

    @Test func theInterfaceFaceIsTheSystemStack() {
        #expect(Design.Interface.bodySize == 15, "interface type is the platform's own, at the design's iOS scale")
        #expect(Design.Interface.captionSize == 13)
        #expect(Design.Interface.microSize == 11)
    }

    @Test func readingIsSeventeenWithAFiftyPercentLineHeight() {
        #expect(ReadingType.size == 17, "reading type is 17 pt Open Sans")
        #expect(ReadingType.lineSpacing == 8.5, "a 1.5 line height at 17 pt")
    }

    @Test func everyControlMeetsTheTouchTarget() {
        #expect(Design.minimumTarget == 44)
        #expect(Design.Radius.field == 12)
        #expect(Design.Radius.panel == 22)
        #expect(Design.Radius.sheet == 28)
    }
}
