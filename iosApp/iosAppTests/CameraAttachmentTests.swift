import AVFoundation
import SwiftUI
import Testing
import UIKit
@testable import iosApp

/// The camera row existed for months with an empty action and no usage string: a tap did
/// nothing, and had it opened the camera, iOS would have killed the app for the missing
/// purpose sentence. These tests pin the parts that can be checked without a lens.
@MainActor
struct CameraAttachmentTests {

    private func image() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24)).image { context in
            UIColor.systemOrange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 24, height: 24))
        }
    }

    @Test func aCapturedPhotoBecomesAnAttachmentTheGatewayCanTake() throws {
        let attachment = try #require(CameraPicker.Coordinator.attachment(from: image()))

        #expect(attachment.mimeType == "image/jpeg", "uploads take the gateway's own MIME type")
        #expect(attachment.name.hasSuffix(".jpg"))
        #expect(attachment.data.count > 0, "a zero-byte photo would upload and fail silently")
        #expect(attachment.url == nil, "a camera shot has no file on disk to hand over")
    }

    @Test func theNameIsUniquePerShot() throws {
        let first = try #require(CameraPicker.Coordinator.attachment(from: image(), now: Date(timeIntervalSince1970: 1_000)))
        let second = try #require(CameraPicker.Coordinator.attachment(from: image(), now: Date(timeIntervalSince1970: 2_000)))

        #expect(first.name != second.name, "two shots in one second must not collide")
    }

    /// A 12-megapixel shot at `jpegData(compressionQuality: 0.85)` is 6–10 MiB and the gateway
    /// caps a file at 6 MiB, so an unsized capture is a refusal for the reader's own photo.
    @Test func aCapturedPhotoFitsUnderTheGatewaysCeiling() throws {
        let context = CGContext(
            data: nil, width: 4_000, height: 3_000, bitsPerComponent: 8, bytesPerRow: 4_000 * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        )!
        arc4random_buf(context.data!, 4_000 * 3_000 * 4)
        let shot = UIImage(cgImage: context.makeImage()!)
        #expect(
            shot.jpegData(compressionQuality: 0.85)!.count > UploadLimits.fallback.maxFileBytes,
            "the fixture has to be over the ceiling to prove anything"
        )

        let attachment = try #require(CameraPicker.Coordinator.attachment(from: shot))

        #expect(attachment.data.count <= UploadLimits.fallback.maxFileBytes, "under the gateway's 6 MiB ceiling")
        #expect(UIImage(data: attachment.data) != nil, "and still a picture")
    }

    /// A camera switched off in Settings opens a black screen with no explanation, which reads as
    /// a broken camera. The decision is a function of the status so it can be asserted.
    @Test func aCameraTheReaderTurnedOffIsExplainedInsteadOfOpened() {
        #expect(CameraAccess.refusal(for: .denied) == "The camera is off for Nanobot. Turn it on in Settings.")
        #expect(CameraAccess.refusal(for: .restricted) != nil)
        #expect(CameraAccess.refusal(for: .authorized) == nil, "an allowed camera is simply opened")
        #expect(CameraAccess.refusal(for: .notDetermined) == nil, "the system's own prompt is the first answer")
    }

    @Test func theBundleExplainsWhyItWantsTheCamera() {
        let purpose = Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") as? String
        #expect(purpose?.isEmpty == false, "iOS refuses the camera, and kills the app, without this")
    }
}
