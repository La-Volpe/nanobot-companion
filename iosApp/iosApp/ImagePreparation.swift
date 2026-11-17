import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// Where a pick becomes something the gateway takes.
///
/// The phone stores photos as HEIC and the gateway's `UPLOAD_MIME_ALLOWED`
/// (`channels/websocket/attachment_policy.py`) has no HEIC in it, so a library photo was uploaded
/// as a type the gateway refuses, which for the owner read as "the attachments do not work". The
/// same photo is also 12 to 48 megapixels, and a JPEG of that at high quality is over the 6 MiB
/// per-file cap, which is a second refusal the reader cannot do anything about.
///
/// So this decodes through ImageIO (which reads HEIC whatever the MIME label says), re-encodes
/// as JPEG when the type is not on the whitelist, steps quality and then pixels down until the
/// file fits, and makes the card's preview once. It runs off the main actor; see
/// `AttachmentViewModel`, which calls it from a detached task.
struct ImagePreparation {

    /// Enough for a model to read a photographed page, and a JPEG that lands under 6 MiB.
    static let maxDimension = 2048
    /// The grid thumb is 84 pt, and the lightbox opens the same bytes; 640 px is sharp in the
    /// grid and recognisable (if soft) full screen, for tens of kilobytes a card.
    static let previewDimension = 640

    func prepare(_ picked: PickedAttachment, maxBytes: Int, mustConvert: Bool) -> PickedAttachment {
        let source = Self.source(picked.data)
        let preview = Self.jpeg(source, maxDimension: Self.previewDimension, maxBytes: nil, quality: 0.7)

        // A type the gateway takes, already under the ceiling: only the preview is new. Re-encoding
        // a PNG screenshot as JPEG would lose its text for no reason.
        if !mustConvert, picked.size <= maxBytes {
            return Self.keeping(picked, preview: preview)
        }
        // Not something this device can decode (a video, a PDF): hand it over untouched and let
        // the core refuse it in the gateway's own words.
        guard let jpeg = Self.jpeg(source, maxDimension: Self.maxDimension, maxBytes: maxBytes) else {
            return Self.keeping(picked, preview: preview)
        }
        return PickedAttachment(
            name: Self.jpegName(picked.name),
            mimeType: "image/jpeg",
            data: jpeg,
            url: picked.url,
            preview: preview
        )
    }

    /// A camera shot, through the same ceiling: `UIImage.jpegData` at full resolution is a
    /// 6–10 MiB file on a 12-megapixel phone, which the gateway refuses.
    static func jpeg(from image: UIImage, maxDimension: Int = maxDimension, maxBytes: Int) -> Data? {
        var dimension = maxDimension
        var quality: CGFloat = 0.85
        for _ in 0..<6 {
            guard let scaled = scaled(image, maxDimension: dimension),
                  let data = scaled.jpegData(compressionQuality: quality) else { return nil }
            if data.count > maxBytes {
                // Quality first: a smaller quality is a cheaper read, a smaller picture loses text.
                if quality > 0.55 { quality -= 0.2 } else { dimension = dimension * 2 / 3 }
                continue
            }
            return data
        }
        return nil
    }

    /// `IMG_2841.HEIC` → `IMG_2841.jpg`: the name the gateway stores should match the bytes it
    /// stored, because that name comes back in the transcript.
    static func jpegName(_ name: String) -> String {
        let stem = (name as NSString).deletingPathExtension
        return (stem.isEmpty ? "photo" : stem) + ".jpg"
    }

    private static func keeping(_ picked: PickedAttachment, preview: Data?) -> PickedAttachment {
        PickedAttachment(name: picked.name, mimeType: picked.mimeType, data: picked.data, url: picked.url, preview: preview)
    }

    private static func source(_ data: Data) -> CGImageSource? {
        guard !data.isEmpty,
              let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) > 0
        else { return nil }
        return source
    }

    /// A JPEG at most `maxDimension` on the long edge, shrunk until it fits `maxBytes`.
    private static func jpeg(
        _ source: CGImageSource?,
        maxDimension: Int,
        maxBytes: Int?,
        quality: Double = 0.85
    ) -> Data? {
        guard let source else { return nil }
        var dimension = maxDimension
        var attempt = quality
        for _ in 0..<6 {
            guard let image = thumbnail(source, maxDimension: dimension),
                  let data = encode(image, quality: attempt) else { return nil }
            guard let maxBytes, data.count > maxBytes else { return data }
            if attempt > 0.55 { attempt -= 0.2 } else { dimension = dimension * 2 / 3 }
        }
        return nil
    }

    private static func thumbnail(_ source: CGImageSource, maxDimension: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimension,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    private static func encode(_ image: CGImage, quality: Double) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    private static func scaled(_ image: UIImage, maxDimension: Int) -> UIImage? {
        let width = image.size.width * image.scale
        let height = image.size.height * image.scale
        let longest = max(width, height)
        guard longest > 0 else { return nil }
        let factor = min(1, CGFloat(maxDimension) / longest)
        let target = CGSize(width: max(1, (width * factor).rounded()), height: max(1, (height * factor).rounded()))
        if factor == 1 { return image }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        // `draw` is what applies the orientation hint the camera writes into the file.
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}
