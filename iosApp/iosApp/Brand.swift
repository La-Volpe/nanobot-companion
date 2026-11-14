import SwiftUI
import UIKit

/// nanobot's own palette and glyphs, in one place.
///
/// The values are the design system's tokens (`webui/design-handoff/DESIGN.md`, gateway
/// revision 28d73b69): a white canvas with warm neutrals in light mode, charcoal
/// `hsl(0 0% 19%)` in dark mode, and the brand orange held back for identity — the design
/// says primary actions are monochrome, so nothing here paints a button orange.
///
/// Icons are not SF Symbols. They come from the WebUI itself: `tools/webui-icons.py` writes
/// them into `Assets.xcassets` as template images, so a row or a button wears the same glyph
/// the browser does and `foregroundStyle` still tints it.
enum Palette {
    /// Page background. `--bg`.
    static let canvas = dynamic(light: 0xFFFFFF, dark: 0x303030)
    /// A raised surface: composer, cards. `--card`.
    static let surface = dynamic(light: 0xFFFFFF, dark: 0x383838)
    /// The quieter fill used behind fields, thumbnails and the search box. `--secondary`.
    static let secondarySurface = dynamic(light: 0xF5F5F5, dark: 0x404040)
    /// The drawer's warm panel, distinct from the canvas. `--sidebar`.
    static let sidebar = dynamic(light: 0xF7F7F6, dark: 0x383838)
    /// The selected row in the drawer. `--selected`.
    static let selected = dynamic(light: 0xE4E4E4, dark: 0x4C4C4C)
    /// Hairlines and field outlines, used sparingly. `--border`.
    static let border = dynamic(light: 0xE9E7E5, dark: 0x474747)
    /// Principal text. `--fg`.
    static let foreground = dynamic(light: 0x1E1E20, dark: 0xF4F4F5)
    /// Metadata, captions, placeholders. `--muted-fg`.
    static let mutedForeground = dynamic(light: 0x62626A, dark: 0xB8B8B8)
    /// Primary action fill. Monochrome on purpose. `--primary`.
    static let primary = dynamic(light: 0x27272A, dark: 0xFAFAFA)
    /// Text on `primary`. `--primary-fg`.
    static let onPrimary = dynamic(light: 0xFAFAFA, dark: 0x171717)
    /// Errors. Never the orange. `--destructive`.
    static let destructive = dynamic(light: 0xB91C1C, dark: 0xFCA5A5)
    /// The user's own message bubble. `--user-bubble`.
    static let bubble = dynamic(light: 0xF5F5F5, dark: 0x404040)
    /// Code blocks. `--code-bg`.
    static let code = dynamic(light: 0xF8F8F8, dark: 0x2A2A2D)
    /// Drawer text: quieter than `foreground`, warmer than a system gray. `--sidebar-fg`.
    static let sidebarForeground = dynamic(light: 0x4A4A4F, dark: 0xD5D5D7)
    /// Warnings and the connecting dot. Amber 700 / amber 300, per the design's status rules.
    static let warning = dynamic(light: 0xB45309, dark: 0xFCD34D)
    /// Success pills and completed turns. Emerald 700 / emerald 300.
    static let success = dynamic(light: 0x047857, dark: 0x6EE7B7)
    /// The mark's two oranges, shared by both modes. `--brand-orange`.
    static let brandOrange = Color(hex: 0xEF8E30)
    static let brandOrangeLight = Color(hex: 0xF4A949)

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

extension UIColor {
    /// `0xEF8E30` → sRGB. The tokens arrive as hex, so the mapping stays literal.
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension Color {
    init(hex: UInt32) { self.init(uiColor: UIColor(hex: hex)) }
}

extension Image {
    /// A glyph from nanobot's WebUI, tinted like an SF Symbol would be.
    static func webui(_ name: String) -> Image {
        Image("Icon\(name)").renderingMode(.template)
    }
}

/// A WebUI glyph at a fixed point size. The drawings are 24×24 with a 1.75 or 2 pt stroke, so
/// they need an explicit frame where an SF Symbol could have leaned on a font size.
struct WebUIIcon: View {
    let name: String
    var size: CGFloat = 16

    var body: some View {
        Image.webui(name)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
    }
}

/// The nanobot mark, day art in light mode and the sleeping-cap variant in dark (the asset
/// catalog carries both as a luminosity pair, so no branching here).
struct NanobotMark: View {
    var height: CGFloat = 26

    var body: some View {
        Image("NanobotMark")
            .resizable()
            .scaledToFit()
            .frame(height: height)
            .accessibilityHidden(true)
    }
}
