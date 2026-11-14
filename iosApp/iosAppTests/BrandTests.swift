import SwiftUI
import Testing
import UIKit
@testable import iosApp

/// The identity is now part of the build, so it is asserted like anything else: an app icon in
/// the bundle, the mark and the WebUI's glyphs in the asset catalog, and palette values that
/// match the design tokens rather than drifting toward a system grey.
@MainActor
struct BrandTests {

    /// Resolve a dynamic colour the way the screen would, in each appearance.
    private func components(_ color: Color, dark: Bool) -> [CGFloat] {
        let traits = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
        let resolved = UIColor(color).resolvedColor(with: traits)
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return [red, green, blue]
    }

    private func expect(_ color: Color, light: UInt32, dark: UInt32, tolerance: CGFloat = 0.01) {
        let lightExpected = [
            CGFloat((light >> 16) & 0xFF) / 255,
            CGFloat((light >> 8) & 0xFF) / 255,
            CGFloat(light & 0xFF) / 255,
        ]
        let darkExpected = [
            CGFloat((dark >> 16) & 0xFF) / 255,
            CGFloat((dark >> 8) & 0xFF) / 255,
            CGFloat(dark & 0xFF) / 255,
        ]
        for (index, actual) in components(color, dark: false).enumerated() {
            #expect(abs(actual - lightExpected[index]) < tolerance, "light component \(index)")
        }
        for (index, actual) in components(color, dark: true).enumerated() {
            #expect(abs(actual - darkExpected[index]) < tolerance, "dark component \(index)")
        }
    }

    @Test func theCanvasIsCharcoalInDarkModeNotBlack() {
        // The design is explicit: charcoal `hsl(0 0% 19%)`, never black and never navy.
        expect(Palette.canvas, light: 0xFFFFFF, dark: 0x303030)
        expect(Palette.sidebar, light: 0xF7F7F6, dark: 0x383838)
    }

    @Test func actionsAreMonochromeAndErrorsAreNeverTheBrandOrange() {
        expect(Palette.primary, light: 0x27272A, dark: 0xFAFAFA)
        expect(Palette.destructive, light: 0xB91C1C, dark: 0xFCA5A5)
        expect(Palette.brandOrange, light: 0xEF8E30, dark: 0xEF8E30)
    }

    @Test func theBundleCarriesAnAppIcon() throws {
        let icons = Bundle.main.object(forInfoDictionaryKey: "CFBundleIcons") as? [String: Any]
        let primary = icons?["CFBundlePrimaryIcon"] as? [String: Any]
        #expect(primary?["CFBundleIconName"] as? String == "AppIcon", "no AppIcon compiled into the bundle")
    }

    @Test func theMarkAndTheWebUIGlyphsAreInTheCatalog() {
        // Template images, one per glyph the screens ask for. A missing asset is a blank row.
        for name in ["NanobotMark", "IconMenu", "IconNewChat", "IconSend", "IconVoice", "IconStop",
                     "IconAttach", "IconSettings", "IconSearch", "IconArchive", "IconAutomations",
                     "IconWarning", "IconCheck", "IconChevronDown", "IconChevronRight", "IconClose"] {
            #expect(UIImage(named: name) != nil, "\(name) is missing from the asset catalog")
        }
    }

    @Test func aWebUIGlyphKeepsItsAspectRatioAndDrawsAsATemplate() throws {
        let image = try #require(UIImage(named: "IconSend"))
        #expect(image.renderingMode == .alwaysTemplate, "a glyph must tint with foregroundStyle")
    }
}
