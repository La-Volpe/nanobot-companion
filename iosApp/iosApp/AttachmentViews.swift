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
    let onTakePhoto: () -> Void
    let onPickFiles: () -> Void

    /// The lens *and* the permission: a camera that is off in Settings is not an option.
    private var cameraAvailable: Bool {
        CameraAccess.isAvailable && CameraAccess.refusal == nil
    }

    /// Which of the two reasons the row is off, because a phone with a lens and a denied
    /// permission is not a phone without a camera.
    private var cameraSubtitle: String {
        if cameraAvailable { return "Take a photo and attach it" }
        return CameraAccess.isAvailable ? "Camera access is off in Settings" : "No camera on this device"
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
                subtitle: cameraSubtitle,
                checked: false,
                identifier: "attach.camera"
            ) {
                model.closeSheet()
                onTakePhoto()
            }
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
            if let notice = model.notice {
                AttachmentNoticeRow(notice: notice, onDismiss: model.dismissNotice)
                    .padding(.top, 6)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
    }
}

/// The attachments above the field: an image grid, a video card and file cards, each with the
/// remove button the design draws on the chip, and the refusal row for a pick that never became
/// one.
struct AttachmentStrip: View {
    @ObservedObject var model: AttachmentViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !model.images.isEmpty {
                HStack(spacing: 6) {
                    ForEach(Array(model.images.enumerated()), id: \.element.id) { index, image in
                        ImageThumb(
                            attachment: image,
                            onTap: { model.openLightbox(at: index) },
                            onRemove: { model.remove(id: image.id) },
                            onRetry: { Task { await model.retry(id: image.id) } }
                        )
                    }
                    Spacer(minLength: 0)
                }
            }
            ForEach(model.videos) { video in
                VideoCard(
                    attachment: video,
                    onRemove: { model.remove(id: video.id) },
                    onRetry: { Task { await model.retry(id: video.id) } }
                )
            }
            ForEach(model.files) { file in
                FileCard(
                    attachment: file,
                    onRemove: { model.remove(id: file.id) },
                    onRetry: { Task { await model.retry(id: file.id) } }
                )
            }
            if let notice = model.notice {
                AttachmentNoticeRow(notice: notice, onDismiss: model.dismissNotice)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The design's `.notice.error` row, with the file named in it: one reason for four picks does
/// not say which pick it was, and the attach sheet is already closed by the time it arrives.
struct AttachmentNoticeRow: View {
    let notice: UploadNotice
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            WebUIIcon(name: "Warning", size: 15)
                .foregroundStyle(Palette.destructive)
            VStack(alignment: .leading, spacing: 2) {
                Text(notice.name)
                    .font(.system(size: 11.5, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .accessibilityIdentifier("attach.rejection.name")
                Text(notice.sentence)
                    .font(.system(size: Design.Interface.microSize))
                    .accessibilityIdentifier("attach.rejection")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Dismiss", action: onDismiss)
                .font(.system(size: 10.5, weight: .semibold))
                .frame(minHeight: Design.minimumTarget / 2)
                .accessibilityIdentifier("attach.rejection.dismiss")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .fill(Palette.destructive.opacity(0.1))
        )
        .foregroundStyle(Palette.destructive)
    }
}

struct ImageThumb: View {
    let attachment: PendingAttachment
    let onTap: () -> Void
    let onRemove: () -> Void
    let onRetry: () -> Void

    var body: some View {
        Button(action: onTap) {
            Group {
                if let image = AttachmentImage.image(for: attachment) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                        .fill(Palette.secondarySurface)
                        .overlay(WebUIIcon(name: "Image", size: 22).foregroundStyle(Palette.mutedForeground))
                }
            }
            .frame(width: Design.Attachment.thumbSize, height: Design.Attachment.thumbSize)
            .clipShape(RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous))
            .overlay {
                // A thumb is too small for a sentence: the spinner says it is on its way, and the
                // failure is a tap on Retry with the reason as the label.
                if attachment.uploading {
                    RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                        .fill(.black.opacity(0.35))
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                        .accessibilityIdentifier("attach.uploading")
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityIdentifier("attach.image")
        .overlay(alignment: .topTrailing) {
            Button(action: onRemove) {
                WebUIIcon(name: "Close", size: 18)
                    .font(.system(size: 16))
                    .foregroundStyle(.white, .black.opacity(0.5))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("attach.remove")
        }
        .overlay(alignment: .bottomTrailing) {
            if attachment.failure != nil {
                Button(action: onRetry) {
                    Text("Retry")
                        .font(.system(size: 9, weight: .semibold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Palette.destructive, in: Capsule())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .padding(3)
                .accessibilityIdentifier("attach.retry")
            }
        }
    }

    private var accessibilityLabel: String {
        if let failure = attachment.failure { return "Retry \(attachment.name): \(failure)" }
        return attachment.uploading ? "Uploading \(attachment.name)" : "Open image \(attachment.name)"
    }
}

struct VideoCard: View {
    let attachment: PendingAttachment
    let onRemove: () -> Void
    let onRetry: () -> Void

    var body: some View {
        AttachmentCard(attachment: attachment, icon: "Video", onRemove: onRemove, onRetry: onRetry)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("attach.video")
    }
}

struct FileCard: View {
    let attachment: PendingAttachment
    let onRemove: () -> Void
    let onRetry: () -> Void

    var body: some View {
        AttachmentCard(attachment: attachment, icon: nil, onRemove: onRemove, onRetry: onRetry)
            // A container identifier without this lands on every leaf instead, which is how a
            // tap on "the card" ends up hitting its close button (AGENTS.md, XCUITest traps).
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("attach.file")
    }
}

/// The chip the design draws (`.attach-chip`), with what the card is doing instead of a size
/// once there is something to say: a spinner and "Uploading…", or the reason and a Retry.
struct AttachmentCard: View {
    let attachment: PendingAttachment
    /// Nil for a file, which shows its type's own face instead of a glyph.
    let icon: String?
    let onRemove: () -> Void
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 9) {
            face
                .frame(width: 26, height: 26)
                .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(attachment.name)
                    .font(.system(size: 11.5))
                    .lineLimit(1)
                    .truncationMode(.middle)
                status
                    .font(.system(size: 10.5))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if attachment.failure != nil {
                Button("Retry", action: onRetry)
                    .font(.system(size: 10.5, weight: .semibold))
                    .frame(minHeight: Design.minimumTarget / 2)
                    .accessibilityIdentifier("attach.retry")
            }
            Button(action: onRemove) { WebUIIcon(name: "Close", size: 13) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("attach.remove")
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(Palette.secondarySurface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    @ViewBuilder
    private var face: some View {
        if let icon {
            WebUIIcon(name: icon, size: 20)
                .foregroundStyle(.secondary)
        } else {
            Text(attachment.kind.face)
                .font(.system(size: 9, weight: .bold))
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var status: some View {
        if attachment.uploading {
            HStack(spacing: 4) {
                ProgressView().controlSize(.mini)
                Text("Uploading…")
            }
            .accessibilityIdentifier("attach.uploading")
        } else if let failure = attachment.failure {
            Text(failure)
                .foregroundStyle(Palette.destructive)
                .lineLimit(1)
                .accessibilityIdentifier("attach.failure")
        } else {
            Text(attachment.sizeLabel)
        }
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
                        WebUIIcon(name: "Close", size: 13)
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
    /// The preview made when the file was picked, off the main actor: decoding a 48-megapixel
    /// original inside a view body costs a frame every time the composer redraws. A file the
    /// picker handed over as a URL falls back to reading it, which only happens for a document
    /// preview.
    static func image(for attachment: PendingAttachment) -> UIImage? {
        if let preview = attachment.preview, let image = UIImage(data: preview) {
            return image
        }
        if let url = attachment.url, let image = UIImage(contentsOfFile: url.path) {
            return image
        }
        return nil
    }
}

/// The pickers, as one modifier so the composer stays a layout: Photos and Files are the system's
/// own sheets, which is why a UI test cannot drive them — it asserts the rows and the state after.
///
/// Both loaders read their bytes in a detached task. `loadTransferable` and `Data(contentsOf:)`
/// on the main actor meant a 12-megapixel HEIC froze the composer for as long as it took to
/// export, which is the other half of "the attachment does not work".
struct AttachmentPickers: ViewModifier {
    @ObservedObject var model: AttachmentViewModel
    @Binding var photosPresented: Bool
    @Binding var filesPresented: Bool
    @State private var selection: [PhotosPickerItem] = []

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
                switch result {
                case let .success(urls):
                    Task { await load(urls) }
                case let .failure(error):
                    // A cancelled sheet is not a failure the reader needs told about.
                    guard (error as NSError).code != NSUserCancelledError else { return }
                    model.note(UploadNotice(name: "Files", sentence: "Those files could not be opened."))
                }
            }
    }

    private func load(_ items: [PhotosPickerItem]) async {
        for item in items {
            let outcome = await Task.detached(priority: .userInitiated) { await PickReader.photo(item) }.value
            await take(outcome)
        }
    }

    private func load(_ urls: [URL]) async {
        for url in urls {
            let outcome = await Task.detached(priority: .userInitiated) { PickReader.file(url) }.value
            await take(outcome)
        }
    }

    /// A pick that could not be read is said out loud: `continue` on a failed transfer was a
    /// silent drop, which reads as the app ignoring the choice.
    private func take(_ outcome: PickOutcome) async {
        switch outcome {
        case let .picked(pick):
            await model.add(pick)
        case let .unreadable(name):
            model.note(UploadNotice(name: name, sentence: "That file could not be read off the phone."))
        }
    }
}

/// What one system picker answer turned into.
enum PickOutcome {
    case picked(PickedAttachment)
    /// The name the reader will recognise, for a file whose bytes never arrived.
    case unreadable(String)
}

/// Where a picker's answer becomes a pick. It sets no limit: the type it declares is the one the
/// gateway's whitelist accepts when the file has a type of its own, and the bytes are made
/// acceptable by `ImagePreparation` in the view model. This only reads, off the main actor.
enum PickReader {

    static func photo(_ item: PhotosPickerItem) async -> PickOutcome {
        let type = item.supportedContentTypes.first
        let name = name(for: type)
        guard let data = try? await item.loadTransferable(type: Data.self), !data.isEmpty else {
            return .unreadable(name)
        }
        return .picked(
            PickedAttachment(
                name: name,
                mimeType: type?.preferredMIMEType ?? "image/jpeg",
                data: data,
                url: nil
            )
        )
    }

    static func file(_ url: URL) -> PickOutcome {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), !data.isEmpty else {
            return .unreadable(url.lastPathComponent)
        }
        return .picked(
            PickedAttachment(
                name: url.lastPathComponent,
                mimeType: mimeType(for: url),
                data: data,
                url: url
            )
        )
    }

    /// What the gateway will be told a picked file is.
    ///
    /// The type the file has is preferred, and the gateway's own whitelist decides whether it
    /// survives: `attachment_policy.py` has no MIME type for source code at all (`.py` is
    /// `text/x-python-script` to iOS and nothing to the gateway), and the design draws exactly
    /// that file as a chip. Text is text to the gateway, so it is declared as `text/plain` and
    /// keeps its name, which is where the model reads `.py`. A file that is neither on the list
    /// nor text keeps its own type and the core refuses it by name.
    static func mimeType(for url: URL) -> String {
        let type = UTType(filenameExtension: url.pathExtension)
        let own = type?.preferredMIMEType
        if let own, UploadWhitelist.takes(own) { return own }
        if type?.conforms(to: .text) == true || type?.conforms(to: .sourceCode) == true { return "text/plain" }
        return own ?? "application/octet-stream"
    }

    /// A picker's photo has no filename, so it gets one the gateway can keep — and one whose
    /// extension follows the bytes it actually carries.
    private static func name(for type: UTType?) -> String {
        let stamp = UUID().uuidString.prefix(8)
        return "photo-\(stamp).\(type?.preferredFilenameExtension ?? "jpg")"
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
