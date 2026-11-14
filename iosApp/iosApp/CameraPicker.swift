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
        static func attachment(from image: UIImage, now: Date = Date()) -> PickedAttachment? {
            guard let data = image.jpegData(compressionQuality: 0.85) else { return nil }
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
