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

    @Test func theBundleExplainsWhyItWantsTheCamera() {
        let purpose = Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") as? String
        #expect(purpose?.isEmpty == false, "iOS refuses the camera, and kills the app, without this")
    }
}
