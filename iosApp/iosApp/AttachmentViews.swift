import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The attach sheet (I6), the cards the picked files become, and the lightbox images open in.
/// The artifact's three rows — Photo or video, Camera, Files — are the three ways in, and each
/// one lands in `AttachmentViewModel.add`, which asks the core whether the gateway will take it.

struct AttachSheet: View {
    let model: AttachmentViewModel
    let onPickPhotos: () -> Void
    let onPickFiles: () -> Void

    private var cameraAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Attach")
                .font(.system(size: Design.Interface.captionSize, weight: .semibold))
                .foregroundStyle(.secondary)
            SheetRow(
                title: "Photo or video",
                subtitle: UploadLimits.attachSheetSubtitle,
                checked: false,
                identifier: "attach.photos"
            ) {
                model.closeSheet()
                onPickPhotos()
            }
            SheetRow(
                title: "Camera",
                subtitle: cameraAvailable ? "Take a photo and attach it" : "No camera on this device",
                checked: false,
                identifier: "attach.camera"
            ) {}
            .disabled(!cameraAvailable)
            .opacity(cameraAvailable ? 1 : 0.5)
            SheetRow(
                title: "Files",
                subtitle: "PDF, documents, text, code",
                checked: false,
                identifier: "attach.files"
            ) {
                model.closeSheet()
                onPickFiles()
            }
            if let rejection = model.lastRejection {
                Text(rejection)
                    .font(.system(size: Design.Interface.microSize))
                    .foregroundStyle(.red)
                    .padding(.top, 6)
                    .accessibilityIdentifier("attach.rejection")
            }
            Spacer(minLength: 0)
        }
        .padding(16)
    }
}

/// The attachments above the field: an image grid, a video card and file cards, each with the
/// remove button the design draws on the chip.
struct AttachmentStrip: View {
    @ObservedObject var model: AttachmentViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !model.images.isEmpty {
                HStack(spacing: 6) {
                    ForEach(Array(model.images.enumerated()), id: \.element.id) { index, image in
                        ImageThumb(attachment: image, onTap: { model.openLightbox(at: index) }, onRemove: { model.remove(id: image.id) })
                    }
                    Spacer(minLength: 0)
                }
            }
            ForEach(model.videos) { video in
                VideoCard(attachment: video, onRemove: { model.remove(id: video.id) })
            }
            ForEach(model.files) { file in
                FileCard(attachment: file, onRemove: { model.remove(id: file.id) })
            }
            if let rejection = model.lastRejection {
                Text(rejection)
                    .font(.system(size: Design.Interface.microSize))
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("attach.rejection")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ImageThumb: View {
    let attachment: PendingAttachment
    let onTap: () -> Void
    let onRemove: () -> Void

    var body: some View {
        Button(action: onTap) {
            Group {
                if let image = AttachmentImage.image(for: attachment) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                        .fill(Color(.secondarySystemBackground))
                        .overlay(Image(systemName: "photo").foregroundStyle(.secondary))
                }
            }
            .frame(width: Design.Attachment.thumbSize, height: Design.Attachment.thumbSize)
            .clipShape(RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open image \(attachment.name)")
        .accessibilityIdentifier("attach.image")
        .overlay(alignment: .topTrailing) {
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(.white, .black.opacity(0.5))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("attach.remove")
        }
    }
}

struct VideoCard: View {
    let attachment: PendingAttachment
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "play.rectangle")
                .font(.system(size: 15))
                .frame(width: 26, height: 26)
                .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(attachment.name)
                    .font(.system(size: 11.5))
                    .lineLimit(1)
                Text(attachment.sizeLabel)
                    .font(.system(size: 10.5))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Button(action: onRemove) { Image(systemName: "xmark") }
                .buttonStyle(.plain)
                .accessibilityIdentifier("attach.remove")
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityIdentifier("attach.video")
    }
}

struct FileCard: View {
    let attachment: PendingAttachment
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 9) {
            Text(attachment.kind.face)
                .font(.system(size: 9, weight: .bold))
                .frame(width: 26, height: 26)
                .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(attachment.name)
                    .font(.system(size: 11.5))
                    .lineLimit(1)
                Text(attachment.sizeLabel)
                    .font(.system(size: 10.5))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Button(action: onRemove) { Image(systemName: "xmark") }
                .buttonStyle(.plain)
                .accessibilityIdentifier("attach.remove")
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityIdentifier("attach.file")
    }
}

/// Full-surface image preview with the counter and a close button (artifact: `#lightbox`).
struct LightboxView: View {
    let model: AttachmentViewModel

    var body: some View {
        ZStack {
            Color.black.opacity(0.94).ignoresSafeArea()
            VStack(spacing: 12) {
                HStack {
                    Spacer()
                    Button(action: model.closeLightbox) {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: Design.minimumTarget, height: Design.minimumTarget)
                            .foregroundStyle(.white)
                    }
                    .accessibilityLabel("Close")
                    .accessibilityIdentifier("lightbox.close")
                }
                if let attachment = model.lightboxImage {
                    if let image = AttachmentImage.image(for: attachment) {
                        Image(uiImage: image).resizable().scaledToFit()
                    } else {
                        Text(attachment.name).foregroundStyle(.white)
                    }
                    Text("\((model.lightbox ?? 0) + 1) of \(model.images.count)")
                        .font(.system(size: Design.Interface.microSize))
                        .foregroundStyle(.white.opacity(0.7))
                        .accessibilityIdentifier("lightbox.counter")
                }
            }
            .padding(16)
        }
    }
}

enum AttachmentImage {
    /// A thumbnail from the bytes the picker handed over; no second copy on disk.
    static func image(for attachment: PendingAttachment) -> UIImage? {
        if let url = attachment.url, let data = try? Data(contentsOf: url) {
            return UIImage(data: data)
        }
        return nil
    }
}

/// The pickers, as one modifier so the composer stays a layout: Photos and Files are the system's
/// own sheets, which is why a UI test cannot drive them — it asserts the rows and the state after.
struct AttachmentPickers: ViewModifier {
    @ObservedObject var model: AttachmentViewModel
    @Binding var photosPresented: Bool
    @Binding var filesPresented: Bool
    @State private var selection: [PhotosPickerItem] = []

    private static let imageTypes: [UTType] = [.image, .movie, .video]

    func body(content: Content) -> some View {
        content
            .photosPicker(
                isPresented: $photosPresented,
                selection: $selection,
                maxSelectionCount: UploadLimits.fallback.maxCount,
                matching: .any(of: [.images, .videos])
            )
            .onChange(of: selection) { _, items in
                guard !items.isEmpty else { return }
                let picked = items
                selection = []
                Task { await load(picked) }
            }
            .fileImporter(
                isPresented: $filesPresented,
                allowedContentTypes: [.item],
                allowsMultipleSelection: true
            ) { result in
                guard case let .success(urls) = result else { return }
                Task { await load(urls) }
            }
    }

    private func load(_ items: [PhotosPickerItem]) async {
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let type = item.supportedContentTypes.first ?? .image
            let name = "photo-\(UUID().uuidString.prefix(8)).\(type.preferredFilenameExtension ?? "jpg")"
            await model.add(PickedAttachment(name: name, mimeType: type.preferredMIMEType ?? "image/jpeg", data: data, url: nil))
        }
    }

    private func load(_ urls: [URL]) async {
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { continue }
            let type = UTType(filenameExtension: url.pathExtension)
            await model.add(
                PickedAttachment(
                    name: url.lastPathComponent,
                    mimeType: type?.preferredMIMEType ?? "application/octet-stream",
                    data: data,
                    url: url
                )
            )
        }
    }
}

extension View {
    func attachmentPickers(
        model: AttachmentViewModel,
        photos: Binding<Bool>,
        files: Binding<Bool>
    ) -> some View {
        modifier(AttachmentPickers(model: model, photosPresented: photos, filesPresented: files))
    }
}
