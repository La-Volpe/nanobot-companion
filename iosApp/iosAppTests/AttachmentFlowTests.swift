import Foundation
import Testing
import UIKit
@testable import iosApp

/// What a pick does between the system picker and the message, which is the part the owner's
/// report ("the attachments do not work") landed on.
///
/// Three things were wrong in the same direction. The card only appeared once the upload had
/// finished, so a photo on a slow link looked like nothing had happened at all. A photo the
/// gateway cannot take (an iPhone's HEIC) was uploaded as-is and refused, and a 12-megapixel
/// shot was over the 6 MiB cap and refused the same way. And the sentence that said so was drawn
/// inside a strip that is hidden when there are no cards, so a first pick could fail in complete
/// silence. The rules live in the model, where they can be asserted.
@MainActor
struct AttachmentFlowTests {

    /// An uploader a test can hold open, so the states between the pick and the reference are
    /// observable instead of raced.
    final class GatedUploader: AttachmentDriving {
        let gate = AsyncGate()
        var uploads: [(name: String, mimeType: String, size: Int)] = []
        var outcome: AttachmentOutcome = .uploaded(reference: "ref-1")
        /// The gateway's whitelist, as far as this test cares: HEIC is not on it
        /// (`attachment_policy.py`, `UPLOAD_MIME_ALLOWED`).
        var takesHeic = false

        func upload(name: String, mimeType: String, data: Data) async -> AttachmentOutcome {
            uploads.append((name, mimeType, data.count))
            await gate.wait()
            return outcome
        }

        func accepts(mimeType: String) -> Bool {
            takesHeic || mimeType != "image/heic"
        }
    }

    private let limits = UploadLimits(maxCount: 4, maxFileBytes: 6 * 1024 * 1024, maxTotalBytes: 24 * 1024 * 1024)

    private func image() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { context in
            UIColor.systemOrange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
    }

    /// A photo whose JPEG bytes are bigger than the gateway's 6 MiB ceiling: what a 12-megapixel
    /// camera actually produces at high quality.
    private func noisyPhoto(width: Int = 2_600, height: Int = 2_000) -> Data {
        let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        )!
        arc4random_buf(context.data!, width * height * 4)
        return UIImage(cgImage: context.makeImage()!).jpegData(compressionQuality: 1)!
    }

    // MARK: - the card, before the upload

    /// The card is the promise that the photo is on its way. Uploading first and drawing later
    /// leaves the composer empty for as long as the link takes, which is indistinguishable from
    /// a pick that did nothing.
    @Test func theCardIsOnScreenWhileTheBytesAreStillGoingUp() async {
        let uploader = GatedUploader()
        let model = AttachmentViewModel(driver: uploader, limits: limits)
        let pick = PickedAttachment(name: "IMG_2841.png", mimeType: "image/png", data: image().pngData()!, url: nil)

        let task = Task { await model.add(pick) }
        await waitUntil { !model.attachments.isEmpty }

        #expect(model.attachments.count == 1, "the card is up before a byte has left the phone")
        #expect(model.isUploading, "and the composer can see that it is still uploading")
        #expect(model.attachments.first?.reference == nil, "there is no reference to send yet")
        #expect(model.attachments.first?.uploading == true)

        await uploader.gate.open()
        await task.value

        #expect(model.isUploading == false)
        #expect(model.attachments.first?.reference == "ref-1", "the gateway's reference lands on the card")
    }

    // MARK: - a failure stays, and can be tried again

    /// A transport failure is worth another try, so its card stays on screen with the reason on
    /// it. The old model replaced the card with a sentence the strip could not even show.
    @Test func aFailedUploadKeepsItsCardAndSaysWhy() async {
        let uploader = GatedUploader()
        uploader.outcome = .failed
        await uploader.gate.open()
        let model = AttachmentViewModel(driver: uploader, limits: limits)

        await model.add(PickedAttachment(name: "IMG_2841.png", mimeType: "image/png", data: image().pngData()!, url: nil))

        #expect(model.attachments.count == 1, "a failed upload is still the reader's photo")
        #expect(model.attachments.first?.failure == "The upload did not reach the gateway.")
        #expect(model.references.isEmpty, "and it is not on the message")
        #expect(model.showsStrip, "so the strip is drawn even though there is no reference yet")
    }

    /// Retry has to upload the bytes the card already holds: sending the reader back to the photo
    /// library to find the same picture again is not a retry.
    @Test func retryingAFailedCardUploadsItAgain() async {
        let uploader = GatedUploader()
        uploader.outcome = .failed
        await uploader.gate.open()
        let model = AttachmentViewModel(driver: uploader, limits: limits)
        await model.add(PickedAttachment(name: "IMG_2841.png", mimeType: "image/png", data: image().pngData()!, url: nil))

        uploader.outcome = .uploaded(reference: "ref-2")
        await model.retry(id: try! #require(model.attachments.first).id)

        #expect(uploader.uploads.count == 2, "the same pick went up twice, not zero times")
        #expect(model.attachments.first?.reference == "ref-2")
        #expect(model.attachments.first?.failure == nil, "the reason goes when the upload lands")
    }

    // MARK: - a photo the gateway cannot take

    /// The owner's phone takes HEIC photos by default and `attachment_policy.py` has no HEIC in
    /// its whitelist, so this is the difference between "the camera works" and a refusal.
    @Test func aPhotoTheGatewayCannotTakeGoesUpAsJpeg() async {
        let uploader = GatedUploader()
        await uploader.gate.open()
        let model = AttachmentViewModel(driver: uploader, limits: limits)

        await model.add(PickedAttachment(name: "IMG_2841.HEIC", mimeType: "image/heic", data: image().pngData()!, url: nil))

        #expect(uploader.uploads.first?.mimeType == "image/jpeg", "re-encoded to a type the gateway takes")
        #expect(uploader.uploads.first?.name == "IMG_2841.jpg", "the name follows the bytes")
        #expect(model.attachments.first?.kind == .image, "and it is still an image card")
    }

    /// A 12-megapixel JPEG is several megabytes; the gateway caps a file at 6 MiB. Uploading it
    /// as-is is a refusal the reader cannot do anything about, so it is shrunk first.
    @Test func aHugePhotoIsShrunkUnderTheGatewaysCeiling() async {
        let uploader = GatedUploader()
        await uploader.gate.open()
        let model = AttachmentViewModel(driver: uploader, limits: limits)

        let big = noisyPhoto()
        #expect(big.count > limits.maxFileBytes, "the fixture has to be over the ceiling to prove anything")

        await model.add(PickedAttachment(name: "IMG_2842.jpg", mimeType: "image/jpeg", data: big, url: nil))

        let uploaded = try! #require(uploader.uploads.first)
        #expect(uploaded.size <= limits.maxFileBytes, "and under the gateway's 6 MiB ceiling")
        #expect(uploaded.mimeType == "image/jpeg")
    }

    /// A type the gateway takes and bytes under the ceiling are left alone: re-encoding a PNG
    /// screenshot as JPEG would lose the text the model is supposed to read.
    @Test func aPhotoTheGatewayTakesIsUploadedUntouched() async {
        let uploader = GatedUploader()
        await uploader.gate.open()
        let model = AttachmentViewModel(driver: uploader, limits: limits)
        let png = image().pngData()!

        await model.add(PickedAttachment(name: "shot.png", mimeType: "image/png", data: png, url: nil))

        #expect(uploader.uploads.first?.size == png.count, "the bytes the picker handed over")
        #expect(uploader.uploads.first?.mimeType == "image/png")
    }

    // MARK: - a file from the Files picker

    /// The design's own example file is `attachment_policy.py`, and the gateway's whitelist has
    /// no MIME type for source code. Declaring the picker's `application/octet-stream` for it is
    /// a refusal for a file the design draws as an ordinary chip.
    @Test func aCodeFileIsDeclaredAsTextTheGatewayTakes() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("attachment_policy.py")
        try Data("print('hi')\n".utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        guard case let .picked(pick) = PickReader.file(url) else {
            Issue.record("a readable file is a pick, not a failure")
            return
        }

        #expect(pick.mimeType == "text/plain", "text is text to the gateway, and it has no .py type")
        #expect(pick.name == "attachment_policy.py", "the name keeps the extension the model reads")
    }

    // MARK: - a refusal names the file

    /// One sentence with no file in it cannot tell the reader which of four picks was refused,
    /// and the sheet that shows it is closed by the time the upload is answered.
    @Test func aRefusalNamesTheFileItRefused() async {
        let uploader = GatedUploader()
        uploader.outcome = .rejected(.unsupported)
        await uploader.gate.open()
        let model = AttachmentViewModel(driver: uploader, limits: limits)

        await model.add(PickedAttachment(name: "IMG_2841.heic", mimeType: "image/heic", data: Data([0, 1, 2, 3]), url: nil))

        #expect(model.notice?.name == "IMG_2841.heic", "the file the gateway refused")
        #expect(model.notice?.sentence == "That file type is not one the gateway accepts.", "with the reason the core gave")
        #expect(model.showsStrip, "and the composer draws it even though there is no card")
        #expect(model.attachments.isEmpty, "a file the gateway will refuse again is not a card")
    }

    /// The notice is the reader's only way to learn that a pick failed, so it has to be put away
    /// by hand rather than reappear with the next redraw.
    @Test func aRefusalIsDismissable() async {
        let uploader = GatedUploader()
        uploader.outcome = .rejected(.empty)
        await uploader.gate.open()
        let model = AttachmentViewModel(driver: uploader, limits: limits)
        await model.add(PickedAttachment(name: "empty.md", mimeType: "text/markdown", data: Data(), url: nil))

        model.dismissNotice()

        #expect(model.notice == nil)
        #expect(model.lastRejection == nil, "the sheet's copy goes with it")
        #expect(model.showsStrip == false, "and the composer is back to its own height")
    }
}
