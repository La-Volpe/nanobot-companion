import AVFoundation
import SwiftUI
import UIKit

/// The camera the attach sheet has always promised and never opened: its row existed with an
/// empty action, and no usage string, so tapping it did nothing and asking for the camera
/// without one would have killed the app on the first shot.
///
/// The picture becomes an ordinary `PickedAttachment`, so it travels the same upload path as a
/// library photo — one door, not two.
struct CameraPicker: UIViewControllerRepresentable {
    let onCapture: (PickedAttachment) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onCapture: onCapture, dismiss: { dismiss() }) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let onCapture: (PickedAttachment) -> Void
        private let dismiss: () -> Void

        init(onCapture: @escaping (PickedAttachment) -> Void, dismiss: @escaping () -> Void) {
            self.onCapture = onCapture
            self.dismiss = dismiss
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage, let attachment = Self.attachment(from: image) {
                onCapture(attachment)
            }
            dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            dismiss()
        }

        /// A camera image has no filename, so it gets one the gateway can keep, and JPEG bytes
        /// rather than a `UIImage`: uploads take data, not a UIKit type.
        ///
        /// The bytes are also shrunk here rather than uploaded as shot. `jpegData` at full
        /// resolution is 6–10 MiB on a 12-megapixel phone and the gateway caps a file at 6 MiB,
        /// so the unshrunk shot came back as a refusal for the reader's own camera roll.
        static func attachment(
            from image: UIImage,
            now: Date = Date(),
            maxBytes: Int = UploadLimits.fallback.maxFileBytes
        ) -> PickedAttachment? {
            guard let data = ImagePreparation.jpeg(from: image, maxBytes: maxBytes) else { return nil }
            let stamp = Int(now.timeIntervalSince1970)
            return PickedAttachment(
                name: "camera-\(stamp).jpg",
                mimeType: "image/jpeg",
                data: data,
                url: nil
            )
        }
    }
}

/// Whether opening the camera is the right thing to do at all. Without this, a camera the reader
/// switched off in Settings opens to a black screen with no explanation — which reads as the
/// camera being broken rather than being off.
enum CameraAccess {

    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    /// The sentence the attach sheet shows when the camera must not be opened, or nil to open it.
    /// `notDetermined` is nil on purpose: the system's own prompt is the first answer.
    static func refusal(for status: AVAuthorizationStatus) -> String? {
        switch status {
        case .denied, .restricted:
            "The camera is off for Nanobot. Turn it on in Settings."
        default:
            nil
        }
    }

    static var refusal: String? { refusal(for: AVCaptureDevice.authorizationStatus(for: .video)) }
}
