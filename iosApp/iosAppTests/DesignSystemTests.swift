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

    /// The surfaces this phase added keep the same mapping: a row a thumb can land on, a
    /// monospaced diff, and reading type that follows the reader's own size.
    @Test func theActivityAndComposerSurfacesKeepTheTouchTarget() {
        #expect(Design.Activity.rowHeight >= Design.minimumTarget, "a tool row is a target, not a caption")
        #expect(Design.Attachment.thumbSize >= Design.minimumTarget, "an image thumbnail is tappable")
        #expect(Design.Activity.reasoningSize < ReadingType.size, "reasoning is reading type, a shade down")
        #expect(Design.Activity.diffSize < Design.Interface.captionSize * 1.2, "the diff is the smallest mono type")
    }

    /// The two voice numbers are both the platform's: the tap threshold the artifact draws and
    /// the gateway's own floor, which the app must not cross.
    @Test func theVoiceThresholdsAreBothTheDesigns() {
        #expect(Design.tapThresholdMs == 300, "the artifact arms tap-to-dictate under 300 ms")
        #expect(Design.tapThresholdMs < VoiceLimits.floorMs, "and the 650 ms floor is the gateway's, above it")
        #expect(VoiceLimits.ceilingMs == 120_000, "120 s is the gateway's own ceiling")
    }

    /// Appearance moves the reading scale and nothing else (design README, "Platform adaptation").
    @Test func theReadingScaleMovesProseCodeAndComposerOnly() {
        #expect(ReadingScale.size(for: .small) == 15)
        #expect(ReadingScale.size(for: .medium) == 16)
        #expect(ReadingScale.size(for: .large) == 18)
        #expect(ReadingScale.size(for: .large) != ReadingType.size, "the preference is its own scale, not the base token")
    }

    /// The activity cluster picks its glyph by the tool's name, which is the design's own rule,
    /// and the glyph is one the WebUI draws (`tools/webui-icons.py`): an SF Symbol here is how the
    /// phone stops matching the browser (AGENTS.md, "gotchas", 25).
    @Test func theToolGlyphComesFromTheToolName() throws {
        #expect(Design.Activity.icon(forTool: "run_command") == "Terminal")
        #expect(Design.Activity.icon(forTool: "web_search") == "Globe")
        #expect(Design.Activity.icon(forTool: "edit_file") == "Compact")
        #expect(Design.Activity.icon(forTool: "read_file") == "Activity")

        // A name with no imageset behind it draws nothing at all, so the catalog is checked too.
        for tool in ["run_command", "web_search", "edit_file", "read_file"] {
            let name = "Icon\(Design.Activity.icon(forTool: tool))"
            #expect(try #require(UIImage(named: name), "\(name) is missing from the asset catalog") != nil)
        }
    }

    @Test func everyControlMeetsTheTouchTarget() {
        #expect(Design.minimumTarget == 44)
        #expect(Design.Radius.field == 12)
        #expect(Design.Radius.panel == 22)
        #expect(Design.Radius.sheet == 28)
    }
}
