import Foundation
import SwiftUI

/// The design system's platform mapping in one place (design README, "Platform adaptation"),
/// so a screen never spells a point size or a radius out of thin air and the third G2 item has
/// something concrete to point at.
///
/// - interface type is the system stack at 15 / 13 / 11,
/// - reading type is Open Sans at 17 with a 1.5 line height (bundled, see `Resources/`),
/// - corners are continuous 12 / 18 / 22 / 28,
/// - every control is at least 44 pt tall.
enum Design {

    enum Radius {
        static let field: CGFloat = 12
        static let bubble: CGFloat = 18
        static let panel: CGFloat = 22
        static let sheet: CGFloat = 28
    }

    /// The design's 44 pt rule, used by every tappable row and control.
    static let minimumTarget: CGFloat = 44

    /// The warm orange from the design system: identity only — the mark, the Full-access
    /// warning and status accents. It is never a primary button and never a glow. The value is
    /// the token itself (`#ef8e30`), shared with `Palette` so there is one orange in the app.
    static let brand = Palette.brandOrange

    /// The composer's bottom inset (`--status-h` plus the design's own padding on iOS).
    static let composerBottomPadding: CGFloat = 12

    /// How far up a hold has to travel before it locks: past this the finger may leave the screen
    /// and the button becomes Send (T48). A distance, not a delay — nothing about starting a
    /// recording waits for a clock any more.
    static let lockDragDistance: CGFloat = 64

    /// The attachment grid's thumbnail (artifact: `.img-thumb` is 124 px wide; the phone's
    /// 3-up grid with 6 pt gaps works out at 84).
    enum Attachment {
        static let thumbSize: CGFloat = 84
    }

    /// The interface scale from the design README (iOS: 15 / 13 / 11). The sizes are constants
    /// so a test can assert the mapping instead of trusting the call site.
    enum Interface {
        static let bodySize: CGFloat = 15
        static let captionSize: CGFloat = 13
        static let microSize: CGFloat = 11

        static let body = Font.system(size: bodySize)
        static let caption = Font.system(size: captionSize)
        static let micro = Font.system(size: microSize)
        static let title = Font.system(size: 24, weight: .semibold)
        static let label = Font.system(size: microSize, weight: .semibold)
    }

    /// The activity cluster's numbers (artifact: `.reasoning`, `.tool-row`, `.diff`).
    enum Activity {
        /// Reasoning is reading text, two points under the reading scale.
        static let reasoningSize: CGFloat = 15
        /// The diff is the smallest type in the app and is monospaced.
        static let diffSize: CGFloat = 11.5
        /// A tool row is a row a thumb can land on, like every other row here.
        static let rowHeight: CGFloat = 44

        /// The design picks the glyph by the tool's name, not by its arguments — and the glyph is
        /// the WebUI's own drawing (`tools/webui-icons.py`), never an SF Symbol for a tool the web
        /// draws (`ic_webui_terminal`, `ic_webui_globe`, `ic_webui_activity`).
        static func icon(forTool name: String) -> String {
            switch name {
            case "run_command", "exec", "shell", "run_shell", "execute_command": "Terminal"
            case "web_search", "search", "x_search": "Globe"
            case "edit_file", "write_file", "apply_patch": "Compact"
            default: "Activity"
            }
        }
    }
}

/// The reading size the user chose (Settings → Appearance), carried through the environment so
/// prose, code and the composer follow it and navigation does not.
private struct ReadingSizeKey: EnvironmentKey {
    static let defaultValue: CGFloat = ReadingType.size
}

extension EnvironmentValues {
    var readingSize: CGFloat {
        get { self[ReadingSizeKey.self] }
        set { self[ReadingSizeKey.self] = newValue }
    }
}

/// Reading type: Open Sans, shipped with the app under the SIL OFL (see `Resources/OpenSans-OFL.txt`).
/// The variable font is registered by `UIAppFonts`; iOS picks the instance from the weight.
enum ReadingType {
    /// The family name the bundled variable font registers under; the design's reading face.
    static let family = "Open Sans"

    /// 17 pt at a 1.5 line height, the iOS reading scale from the design README.
    static let size: CGFloat = 17
    static let lineSpacing: CGFloat = size * 0.5

    static func font(size: CGFloat = ReadingType.size, weight: Font.Weight = .regular) -> Font {
        .custom(family, size: size).weight(weight)
    }

    static let body = font()
}
