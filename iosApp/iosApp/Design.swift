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
