import SwiftUI

/// The placeholder shapes a screen draws while a read is in flight, so "not loaded yet" never reads
/// as "nothing here" (the WebUI's `StartupSkeleton`/`ThreadLoadingSkeleton` draw the same idea).
///
/// Static on purpose: the WebUI animates its blocks, but a phone that pulses for a read the local
/// gateway answers in 40 ms looks broken, and a still shape reads as "coming" just as well.
struct SkeletonLine: View {
    var width: CGFloat? = nil
    var height: CGFloat = 12
    var corner: CGFloat = 4

    var body: some View {
        RoundedRectangle(cornerRadius: corner, style: .continuous)
            .fill(Palette.foreground.opacity(0.08))
            .frame(maxWidth: width == nil ? .infinity : width, minHeight: height, maxHeight: height)
    }
}

/// `count` rows at the size of the real ones: a surface with two lines in it, which is what a
/// drawer row, an automation card and a settings row all are.
struct SkeletonRows: View {
    var count: Int = 5
    var rowHeight: CGFloat = 44
    var spacing: CGFloat = 2
    /// What VoiceOver announces instead of the shapes, so the state is audible as well as visible.
    var label: String = "Loading"

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) {
            ForEach(0..<count, id: \.self) { _ in
                VStack(alignment: .leading, spacing: 6) {
                    SkeletonLine(width: nil, height: 10)
                        .frame(maxWidth: .infinity)
                        .padding(.trailing, 60)
                    SkeletonLine(width: nil, height: 8)
                        .padding(.trailing, 140)
                }
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, minHeight: rowHeight, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Palette.secondarySurface)
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("skeleton")
        .accessibilityLabel(label)
    }
}
